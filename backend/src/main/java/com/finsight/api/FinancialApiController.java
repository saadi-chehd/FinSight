package com.finsight.api;

import com.finsight.api.FinancialApiModels.CashFlowResponse;
import com.finsight.api.FinancialApiModels.InvoiceSummaryResponse;
import com.finsight.api.FinancialApiModels.SmeSummary;
import com.finsight.api.FinancialApiModels.TransactionsResponse;
import com.finsight.financial.FinancialModels.OpeningCash;
import com.finsight.financial.FinancialReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only HTTP API exposing SME listings, cash-flow reports, invoice summaries, and transactions. */
@RestController
@RequestMapping("/api/smes")
public class FinancialApiController {
    private final FinancialApiRepository repository;
    private final FinancialReportService reportService;

    public FinancialApiController(FinancialApiRepository repository, FinancialReportService reportService) {
        this.repository = repository;
        this.reportService = reportService;
    }

    @GetMapping
    public List<SmeSummary> listSmes() {
        return repository.listSmes();
    }

    @GetMapping("/{smeId}/cash-flow")
    public CashFlowResponse cashFlow(
        @PathVariable long smeId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openingDate,
        @RequestParam String openingAmount,
        @RequestParam String currency,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        BigDecimal parsedAmount = MoneyCodec.parseExact(openingAmount);
        String validCurrency = MoneyCodec.currency(currency);
        return CashFlowResponse.from(
            reportService.dailyCashFlow(smeId, new OpeningCash(openingDate, validCurrency, parsedAmount), endDate)
        );
    }

    @GetMapping("/{smeId}/invoices/summary")
    public InvoiceSummaryResponse invoiceSummary(
        @PathVariable long smeId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate
    ) {
        return InvoiceSummaryResponse.from(reportService.invoiceSummary(smeId, asOfDate));
    }

    @GetMapping("/{smeId}/transactions")
    public TransactionsResponse transactions(
        @PathVariable long smeId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        if (smeId <= 0) {
            throw new IllegalArgumentException("SME ID must be positive");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("End date must not precede start date");
        }
        return repository.findTransactions(smeId, from, to);
    }
}
