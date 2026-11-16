package com.finsight.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.finsight.financial.FinancialModels.*;

/** Pure deterministic arithmetic: no database, wall clock, ML, or invoice-to-cash assumptions. */
@Component
public class FinancialCalculator {
    public CashFlowReport cashFlow(long smeId, OpeningCash opening, LocalDate endDate,
                                   List<Movement> movements, BigDecimal warningThreshold, int maxDays) {
        validSme(smeId);
        required(opening, "Opening cash");
        required(opening.date(), "Opening date");
        required(endDate, "End date");
        String currency = Money.currency(opening.currency());
        BigDecimal balance = Money.exact(opening.amount());
        BigDecimal threshold = Money.threshold(warningThreshold);
        long dayCount = ChronoUnit.DAYS.between(opening.date(), endDate) + 1;
        if (maxDays < 1 || dayCount < 1 || dayCount > maxDays) {
            throw new IllegalArgumentException("Date range must be ordered and within the configured day limit");
        }
        validateMovements(movements, currency);
        Map<LocalDate, BigDecimal[]> totals = new HashMap<>();
        for (Movement movement : movements) {
            if (movement.date().isBefore(opening.date()) || movement.date().isAfter(endDate)) continue;
            BigDecimal[] day = totals.computeIfAbsent(movement.date(), ignored -> new BigDecimal[]{Money.ZERO, Money.ZERO});
            int index = movement.direction() == Direction.INFLOW ? 0 : 1;
            day[index] = day[index].add(movement.amount());
        }
        List<DailyCashFlow> days = new ArrayList<>();
        List<LiquidityWarning> warnings = new ArrayList<>();
        for (long offset = 0; offset < dayCount; offset++) {
            LocalDate date = opening.date().plusDays(offset);
            BigDecimal[] day = totals.getOrDefault(date, new BigDecimal[]{Money.ZERO, Money.ZERO});
            BigDecimal inflow = Money.exact(day[0]);
            BigDecimal outflow = Money.exact(day[1]);
            BigDecimal net = Money.exact(inflow.subtract(outflow));
            BigDecimal closing = Money.exact(balance.add(net));
            days.add(new DailyCashFlow(date, balance, inflow, outflow, net, closing));
            if (closing.compareTo(threshold) < 0) {
                WarningKind kind = closing.signum() < 0 ? WarningKind.POTENTIAL_CASH_SHORTFALL : WarningKind.BELOW_CONFIGURED_BUFFER;
                warnings.add(new LiquidityWarning(date, kind, closing, threshold, Money.exact(threshold.subtract(closing))));
            }
            balance = closing;
        }
        return new CashFlowReport(smeId, new OpeningCash(opening.date(), currency, Money.exact(opening.amount())),
            endDate, threshold, days, warnings);
    }

    public InvoiceSummary invoiceSummary(long smeId, String currency, LocalDate asOf,
                                         List<Invoice> invoices, List<Movement> movements) {
        validSme(smeId);
        Money.currency(currency);
        required(asOf, "As-of date");
        required(invoices, "Invoices");
        validateMovements(movements, currency);
        Map<Long, Invoice> byId = new HashMap<>();
        for (Invoice invoice : invoices) {
            required(invoice, "Invoice");
            if (invoice.id() <= 0 || byId.putIfAbsent(invoice.id(), invoice) != null) {
                throw new IllegalArgumentException("Invoice IDs must be positive and unique");
            }
            if (!currency.equals(invoice.currency())) throw new IllegalArgumentException("Invoice currency mismatch");
            if (invoice.reference() == null || invoice.reference().isBlank()) throw new IllegalArgumentException("Invoice reference required");
            required(invoice.direction(), "Invoice direction");
            required(invoice.recordedStatus(), "Invoice status");
            required(invoice.issueDate(), "Issue date");
            required(invoice.dueDate(), "Due date");
            if (invoice.dueDate().isBefore(invoice.issueDate())) throw new IllegalArgumentException("Due date precedes issue date");
            Money.positive(invoice.amount());
        }
        Map<Long, BigDecimal> paid = new HashMap<>();
        for (Movement movement : movements) {
            if (movement.invoiceId() == null) continue;
            Invoice invoice = byId.get(movement.invoiceId());
            if (invoice == null || movement.direction() != invoice.direction()) {
                throw new IllegalArgumentException("Payment must match a supplied invoice and its direction");
            }
            if (!movement.date().isAfter(asOf)) paid.merge(invoice.id(), movement.amount(), BigDecimal::add);
        }
        List<InvoicePosition> positions = new ArrayList<>();
        invoices.stream().sorted(Comparator.comparingLong(Invoice::id)).forEach(invoice -> {
            if (invoice.issueDate().isAfter(asOf)) return;
            BigDecimal face = Money.positive(invoice.amount());
            BigDecimal paidToDate = Money.exact(paid.getOrDefault(invoice.id(), Money.ZERO));
            BigDecimal overpayment = Money.exact(paidToDate.subtract(face).max(Money.ZERO));
            boolean cancelled = invoice.recordedStatus() == PaymentStatus.CANCELLED;
            BigDecimal outstanding = cancelled ? Money.ZERO : Money.exact(face.subtract(paidToDate).max(Money.ZERO));
            PaymentStatus effective = cancelled ? PaymentStatus.CANCELLED :
                paidToDate.signum() == 0 ? PaymentStatus.UNPAID :
                paidToDate.compareTo(face) < 0 ? PaymentStatus.PARTIALLY_PAID : PaymentStatus.PAID;
            boolean overdue = !cancelled && outstanding.signum() > 0 && invoice.dueDate().isBefore(asOf);
            positions.add(new InvoicePosition(invoice.id(), invoice.reference(), invoice.direction(), invoice.dueDate(),
                invoice.recordedStatus(), effective, invoice.recordedStatus() != effective,
                face, paidToDate, outstanding, overpayment, overdue));
        });
        List<InvoiceBucket> buckets = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            for (PaymentStatus status : List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID, PaymentStatus.PAID)) {
                List<InvoicePosition> group = positions.stream().filter(p -> p.direction() == direction && p.effectiveStatus() == status).toList();
                BigDecimal face = Money.ZERO, paidTotal = Money.ZERO, outstanding = Money.ZERO, overdue = Money.ZERO;
                long overdueCount = 0;
                for (InvoicePosition position : group) {
                    face = face.add(position.faceAmount()); paidTotal = paidTotal.add(position.paidToDate());
                    outstanding = outstanding.add(position.outstanding());
                    if (position.overdue()) { overdueCount++; overdue = overdue.add(position.outstanding()); }
                }
                buckets.add(new InvoiceBucket(direction, status, group.size(), Money.exact(face), Money.exact(paidTotal),
                    Money.exact(outstanding), overdueCount, Money.exact(overdue)));
            }
        }
        return new InvoiceSummary(smeId, currency, asOf, positions, buckets);
    }

    private static void validateMovements(List<Movement> movements, String currency) {
        required(movements, "Movements");
        Set<Long> ids = new HashSet<>();
        for (Movement movement : movements) {
            required(movement, "Movement");
            if (movement.id() <= 0 || !ids.add(movement.id())) throw new IllegalArgumentException("Movement IDs must be positive and unique");
            required(movement.date(), "Movement date");
            required(movement.direction(), "Movement direction");
            if (!currency.equals(movement.currency())) throw new IllegalArgumentException("Movement currency mismatch");
            Money.positive(movement.amount());
        }
    }

    private static void validSme(long smeId) {
        if (smeId <= 0) throw new IllegalArgumentException("SME ID must be positive");
    }
    private static void required(Object value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
    }
}
