package com.finsight.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.finsight.financial.FinancialModels.*;

/** Reads a consistent database snapshot for each report; never mutates financial records. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FinancialReportService {
    private final FinancialReadRepository repository;
    private final FinancialCalculator calculator;
    private final BigDecimal threshold;
    private final int maxDays;

    public FinancialReportService(FinancialReadRepository repository, FinancialCalculator calculator,
            @Value("${financial.liquidity-warning-threshold}") BigDecimal threshold,
            @Value("${financial.max-report-days}") int maxDays) {
        this.repository = repository; this.calculator = calculator;
        this.threshold = Money.threshold(threshold);
        if (maxDays < 1) throw new IllegalArgumentException("Maximum report days must be positive");
        this.maxDays = maxDays;
    }

    public CashFlowReport dailyCashFlow(long smeId, OpeningCash opening, LocalDate endDate) {
        if (smeId <= 0 || opening == null || opening.date() == null || endDate == null) {
            throw new IllegalArgumentException("Positive SME ID, dated opening cash and end date required");
        }
        Money.exact(opening.amount());
        Money.currency(opening.currency());
        long days = ChronoUnit.DAYS.between(opening.date(), endDate) + 1;
        if (days < 1 || days > maxDays) throw new IllegalArgumentException("Invalid or oversized date range");
        String currency = repository.currency(smeId);
        if (!currency.equals(opening.currency())) throw new IllegalArgumentException("Opening cash currency does not match SME");
        return calculator.cashFlow(smeId, opening, endDate, repository.movements(smeId, opening.date(), endDate), threshold, maxDays);
    }

    public InvoiceSummary invoiceSummary(long smeId, LocalDate asOfDate) {
        if (smeId <= 0 || asOfDate == null) throw new IllegalArgumentException("Positive SME ID and explicit as-of date required");
        String currency = repository.currency(smeId);
        return calculator.invoiceSummary(smeId, currency, asOfDate, repository.invoices(smeId, asOfDate), repository.invoicePayments(smeId, asOfDate));
    }
}
