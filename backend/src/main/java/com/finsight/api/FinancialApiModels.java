package com.finsight.api;

import com.finsight.financial.FinancialModels.CashFlowReport;
import com.finsight.financial.FinancialModels.DailyCashFlow;
import com.finsight.financial.FinancialModels.Direction;
import com.finsight.financial.FinancialModels.InvoiceBucket;
import com.finsight.financial.FinancialModels.InvoicePosition;
import com.finsight.financial.FinancialModels.InvoiceSummary;
import com.finsight.financial.FinancialModels.LiquidityWarning;
import com.finsight.financial.FinancialModels.PaymentStatus;
import com.finsight.financial.FinancialModels.WarningKind;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** JSON response DTOs for read-only SME financial endpoints. */
public final class FinancialApiModels {
    private FinancialApiModels() { }

    public record SmeSummary(long id, String name, String currency, OffsetDateTime createdAt) { }

    public record OpeningCashView(LocalDate date, String currency, String amount) { }

    public record DailyCashFlowView(
        LocalDate date,
        String openingBalance,
        String inflow,
        String outflow,
        String netMovement,
        String closingBalance
    ) {
        static DailyCashFlowView from(DailyCashFlow day) {
            return new DailyCashFlowView(
                day.date(),
                MoneyCodec.format(day.openingBalance()),
                MoneyCodec.format(day.inflow()),
                MoneyCodec.format(day.outflow()),
                MoneyCodec.format(day.netMovement()),
                MoneyCodec.format(day.closingBalance())
            );
        }
    }

    public record LiquidityWarningView(
        LocalDate date,
        WarningKind kind,
        String closingBalance,
        String threshold,
        String gapToThreshold
    ) {
        static LiquidityWarningView from(LiquidityWarning warning) {
            return new LiquidityWarningView(
                warning.date(),
                warning.kind(),
                MoneyCodec.format(warning.closingBalance()),
                MoneyCodec.format(warning.threshold()),
                MoneyCodec.format(warning.gapToThreshold())
            );
        }
    }

    public record CashFlowResponse(
        long smeId,
        OpeningCashView opening,
        LocalDate endDate,
        String warningThreshold,
        List<DailyCashFlowView> days,
        List<LiquidityWarningView> warnings
    ) {
        public CashFlowResponse {
            days = List.copyOf(days);
            warnings = List.copyOf(warnings);
        }

        public static CashFlowResponse from(CashFlowReport report) {
            return new CashFlowResponse(
                report.smeId(),
                new OpeningCashView(
                    report.opening().date(),
                    report.opening().currency(),
                    MoneyCodec.format(report.opening().amount())
                ),
                report.endDate(),
                MoneyCodec.format(report.warningThreshold()),
                report.days().stream().map(DailyCashFlowView::from).toList(),
                report.warnings().stream().map(LiquidityWarningView::from).toList()
            );
        }
    }

    public record InvoiceBucketView(
        Direction direction,
        PaymentStatus status,
        long count,
        String faceAmount,
        String paidToDate,
        String outstanding,
        long overdueCount,
        String overdueOutstanding
    ) {
        static InvoiceBucketView from(InvoiceBucket bucket) {
            return new InvoiceBucketView(
                bucket.direction(),
                bucket.status(),
                bucket.count(),
                MoneyCodec.format(bucket.faceAmount()),
                MoneyCodec.format(bucket.paidToDate()),
                MoneyCodec.format(bucket.outstanding()),
                bucket.overdueCount(),
                MoneyCodec.format(bucket.overdueOutstanding())
            );
        }
    }

    public record InvoicePositionView(
        long id,
        String reference,
        Direction direction,
        LocalDate dueDate,
        PaymentStatus recordedStatus,
        PaymentStatus effectiveStatus,
        boolean statusMismatch,
        String faceAmount,
        String paidToDate,
        String outstanding,
        String overpayment,
        boolean overdue
    ) {
        static InvoicePositionView from(InvoicePosition position) {
            return new InvoicePositionView(
                position.id(),
                position.reference(),
                position.direction(),
                position.dueDate(),
                position.recordedStatus(),
                position.effectiveStatus(),
                position.statusMismatch(),
                MoneyCodec.format(position.faceAmount()),
                MoneyCodec.format(position.paidToDate()),
                MoneyCodec.format(position.outstanding()),
                MoneyCodec.format(position.overpayment()),
                position.overdue()
            );
        }
    }

    public record InvoiceSummaryResponse(
        long smeId,
        String currency,
        LocalDate asOfDate,
        List<InvoiceBucketView> buckets,
        List<InvoicePositionView> invoices
    ) {
        public InvoiceSummaryResponse {
            buckets = List.copyOf(buckets);
            invoices = List.copyOf(invoices);
        }

        public static InvoiceSummaryResponse from(InvoiceSummary summary) {
            return new InvoiceSummaryResponse(
                summary.smeId(),
                summary.currency(),
                summary.asOfDate(),
                summary.buckets().stream().map(InvoiceBucketView::from).toList(),
                summary.invoices().stream().map(InvoicePositionView::from).toList()
            );
        }
    }

    public record TransactionView(
        long id,
        Long invoiceId,
        LocalDate bookedDate,
        String currency,
        Direction direction,
        String transactionType,
        String amount,
        String description
    ) { }

    public record TransactionsResponse(
        long smeId,
        String currency,
        LocalDate from,
        LocalDate to,
        List<TransactionView> transactions
    ) {
        public TransactionsResponse {
            transactions = List.copyOf(transactions);
        }
    }
}
