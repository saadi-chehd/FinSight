package com.finsight.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Immutable calculation inputs/results. No floating point money or implicit current date. */
public final class FinancialModels {
    private FinancialModels() { }
    public enum Direction { INFLOW, OUTFLOW }
    public enum PaymentStatus { UNPAID, PARTIALLY_PAID, PAID, CANCELLED }
    public enum WarningKind { BELOW_CONFIGURED_BUFFER, POTENTIAL_CASH_SHORTFALL }
    public record OpeningCash(LocalDate date, String currency, BigDecimal amount) { }
    public record Movement(long id, Long invoiceId, LocalDate date, String currency,
                           Direction direction, BigDecimal amount) { }
    public record Invoice(long id, String reference, String currency, Direction direction,
                          BigDecimal amount, LocalDate issueDate, LocalDate dueDate,
                          PaymentStatus recordedStatus) { }
    public record LiquidityWarning(LocalDate date, WarningKind kind, BigDecimal closingBalance,
                                   BigDecimal threshold, BigDecimal gapToThreshold) { }
    public record DailyCashFlow(LocalDate date, BigDecimal openingBalance, BigDecimal inflow,
                                BigDecimal outflow, BigDecimal netMovement, BigDecimal closingBalance) { }
    public record CashFlowReport(long smeId, OpeningCash opening, LocalDate endDate,
                                 BigDecimal warningThreshold, List<DailyCashFlow> days,
                                 List<LiquidityWarning> warnings) {
        public CashFlowReport { days = List.copyOf(days); warnings = List.copyOf(warnings); }
    }
    public record InvoicePosition(long id, String reference, Direction direction,
                                  LocalDate dueDate, PaymentStatus recordedStatus,
                                  PaymentStatus effectiveStatus, boolean statusMismatch,
                                  BigDecimal faceAmount, BigDecimal paidToDate,
                                  BigDecimal outstanding, BigDecimal overpayment, boolean overdue) { }
    public record InvoiceBucket(Direction direction, PaymentStatus status, long count,
                                BigDecimal faceAmount, BigDecimal paidToDate, BigDecimal outstanding,
                                long overdueCount, BigDecimal overdueOutstanding) { }
    public record InvoiceSummary(long smeId, String currency, LocalDate asOfDate,
                                 List<InvoicePosition> invoices, List<InvoiceBucket> buckets) {
        public InvoiceSummary { invoices = List.copyOf(invoices); buckets = List.copyOf(buckets); }
    }
}
