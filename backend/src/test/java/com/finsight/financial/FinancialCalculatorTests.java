package com.finsight.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.finsight.financial.FinancialModels.*;
import static org.junit.jupiter.api.Assertions.*;

class FinancialCalculatorTests {
    final FinancialCalculator calculator = new FinancialCalculator();
    final LocalDate start = LocalDate.of(2026, 1, 10);
    static BigDecimal money(String value) { return new BigDecimal(value); }
    OpeningCash opening(String amount) { return new OpeningCash(start, "USD", money(amount)); }
    Movement movement(long id, int day, Direction direction, String amount, Long invoice) {
        return new Movement(id, invoice, start.plusDays(day), "USD", direction, money(amount));
    }
    CashFlowReport flow(String opening, int lastDay, List<Movement> movements, String threshold) {
        return calculator.cashFlow(1, opening(opening), start.plusDays(lastDay), movements, money(threshold), 3660);
    }
    Invoice invoice(long id, Direction direction, String amount, PaymentStatus status, LocalDate issued, LocalDate due) {
        return new Invoice(id, "INV-" + id, "USD", direction, money(amount), issued, due, status);
    }
    Invoice invoice(long id, String amount, PaymentStatus status) {
        return invoice(id, Direction.INFLOW, amount, status, start.minusDays(10), start);
    }
    InvoiceSummary summary(LocalDate asOf, List<Invoice> invoices, List<Movement> payments) {
        return calculator.invoiceSummary(1, "USD", asOf, invoices, payments);
    }
    InvoiceBucket bucket(InvoiceSummary summary, Direction direction, PaymentStatus status) {
        return summary.buckets().stream().filter(b -> b.direction() == direction && b.status() == status).findFirst().orElseThrow();
    }

    @Test
    void rollsForwardMultipleMovementsAndEmptyDaysWithInclusiveBoundaries() {
        var report = flow("100.00", 2, List.of(
            movement(1,0,Direction.INFLOW,"20.10",null), movement(2,0,Direction.INFLOW,"30.00",null),
            movement(3,0,Direction.OUTFLOW,"5.05",null), movement(4,2,Direction.OUTFLOW,"200.00",null),
            movement(5,-1,Direction.INFLOW,"999.00",null), movement(6,3,Direction.INFLOW,"999.00",null)), "0.00");
        assertEquals(3, report.days().size());
        assertEquals(money("50.10"), report.days().get(0).inflow());
        assertEquals(money("5.05"), report.days().get(0).outflow());
        assertEquals(money("45.05"), report.days().get(0).netMovement());
        assertEquals(money("145.05"), report.days().get(0).closingBalance());
        assertEquals(money("145.05"), report.days().get(1).openingBalance());
        assertEquals(money("0.00"), report.days().get(1).netMovement());
        assertEquals(money("145.05"), report.days().get(1).closingBalance());
        assertEquals(money("-54.95"), report.days().get(2).closingBalance());
        assertEquals(start.plusDays(2), report.days().get(2).date());
        assertEquals(1, report.warnings().size());
        assertEquals(WarningKind.POTENTIAL_CASH_SHORTFALL, report.warnings().get(0).kind());
        assertEquals(money("54.95"), report.warnings().get(0).gapToThreshold());
    }

    @Test
    void emptyReportRetainsRequiredOpeningBalanceWithoutInventingMovements() {
        var report = flow("0.00", 1, List.of(), "0.00");
        assertEquals(2, report.days().size());
        assertEquals(money("0.00"), report.days().get(1).closingBalance());
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void acceptsNegativeOpeningCashAndWarnsOnEmptyDay() {
        var report = flow("-10.00", 0, List.of(), "0.00");
        assertEquals(money("-10.00"), report.days().get(0).closingBalance());
        assertEquals(WarningKind.POTENTIAL_CASH_SHORTFALL, report.warnings().get(0).kind());
    }

    @Test
    void decimalArithmeticIsExactAndThresholdComparisonIsStrict() {
        var exact = flow("0.00",0,List.of(movement(1,0,Direction.INFLOW,"0.10",null),movement(2,0,Direction.INFLOW,"0.20",null)),"0.30");
        assertEquals(money("0.30"),exact.days().get(0).closingBalance());
        assertTrue(exact.warnings().isEmpty());
        var buffer = flow("0.30",0,List.of(),"0.31");
        assertEquals(WarningKind.BELOW_CONFIGURED_BUFFER,buffer.warnings().get(0).kind());
        assertEquals(money("0.01"),buffer.warnings().get(0).gapToThreshold());
    }

    @Test
    void leapDayIsIncludedAsCalendarDate() {
        var report = calculator.cashFlow(1,new OpeningCash(LocalDate.of(2024,2,28),"USD",money("1.00")),
            LocalDate.of(2024,3,1),List.of(),money("0.00"),3);
        assertEquals(LocalDate.of(2024,2,29),report.days().get(1).date());
        assertEquals(3,report.days().size());
    }

    @Test
    void maximumBalancedDailyFlowsAreAllowedWithoutIntermediateRounding() {
        var report = flow("0.00",0,List.of(movement(1,0,Direction.INFLOW,Money.MAX.toPlainString(),null),
            movement(2,0,Direction.OUTFLOW,Money.MAX.toPlainString(),null)),"0.00");
        assertEquals(money("0.00"),report.days().get(0).closingBalance());
    }

    @ParameterizedTest
    @ValueSource(strings={"1.001","100000000000000000.00","-100000000000000000.00"})
    void invalidOpeningMoneyIsRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> flow(value,0,List.of(),"0.00"));
    }

    @ParameterizedTest
    @ValueSource(strings={"0.00","-0.01","1.001","100000000000000000.00"})
    void invalidMovementAmountsAreRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",0,List.of(movement(1,0,Direction.INFLOW,value,null)),"0.00"));
    }

    @ParameterizedTest
    @ValueSource(strings={"-0.01","0.001","100000000000000000.00"})
    void invalidThresholdsFailInsteadOfBeingRoundedOrDefaulted(String value) {
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",0,List.of(),value));
    }

    @Test
    void invalidDatesIdsCurrenciesAndMissingInputsFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",-1,List.of(),"0.00"));
        assertThrows(IllegalArgumentException.class, () -> calculator.cashFlow(1,opening("0.00"),start.plusDays(3),List.of(),money("0.00"),3));
        assertThrows(IllegalArgumentException.class, () -> calculator.cashFlow(0,opening("0.00"),start,List.of(),money("0.00"),3));
        assertThrows(IllegalArgumentException.class, () -> calculator.cashFlow(1,null,start,List.of(),money("0.00"),3));
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",0,null,"0.00"));
        assertThrows(IllegalArgumentException.class, () -> calculator.cashFlow(1,new OpeningCash(start,"JPY",money("1.00")),start,List.of(),money("0.00"),3));
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",0,List.of(new Movement(1,null,start,"EUR",Direction.INFLOW,money("1.00"))),"0.00"));
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",0,List.of(new Movement(1,null,start,"USD",null,money("1.00"))),"0.00"));
        var duplicate = movement(1,0,Direction.INFLOW,"1.00",null);
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",0,List.of(duplicate,duplicate),"0.00"));
    }

    @Test
    void calculatedTotalsAndClosingOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> flow(Money.MAX.toPlainString(),0,List.of(movement(1,0,Direction.INFLOW,"0.01",null)),"0.00"));
        assertThrows(IllegalArgumentException.class, () -> flow("0.00",0,List.of(movement(1,0,Direction.INFLOW,Money.MAX.toPlainString(),null),movement(2,0,Direction.INFLOW,"0.01",null)),"0.00"));
    }

    @Test
    void summarizesEffectiveStatusesPartialPaymentsOverpaymentsAndDirections() {
        var invoices = List.of(invoice(1,"100.00",PaymentStatus.PAID),invoice(2,"100.00",PaymentStatus.UNPAID),
            invoice(3,Direction.OUTFLOW,"80.00",PaymentStatus.PAID,start.minusDays(1),start),
            invoice(4,"20.00",PaymentStatus.UNPAID),invoice(5,"50.00",PaymentStatus.CANCELLED),
            invoice(6,Direction.INFLOW,"10.00",PaymentStatus.UNPAID,start.plusDays(2),start.plusDays(5)));
        var payments = List.of(movement(1,0,Direction.INFLOW,"40.00",2L),movement(2,1,Direction.INFLOW,"10.00",2L),
            movement(3,-1,Direction.OUTFLOW,"80.00",3L),movement(4,0,Direction.INFLOW,"25.00",4L),
            movement(5,2,Direction.INFLOW,"100.00",1L),movement(6,0,Direction.INFLOW,"7.00",null));
        var result = summary(start.plusDays(1),invoices,payments);
        assertEquals(5,result.invoices().size());
        assertEquals(6,result.buckets().size());
        assertTrue(result.invoices().get(0).statusMismatch());
        assertEquals(PaymentStatus.UNPAID,result.invoices().get(0).effectiveStatus());
        assertEquals(money("50.00"),result.invoices().get(1).outstanding());
        assertTrue(result.invoices().get(1).statusMismatch());
        assertEquals(money("5.00"),result.invoices().get(3).overpayment());
        assertFalse(result.invoices().get(3).overdue());
        assertEquals(PaymentStatus.CANCELLED,result.invoices().get(4).effectiveStatus());
        assertEquals(money("0.00"),result.invoices().get(4).outstanding());
        var partial = bucket(result,Direction.INFLOW,PaymentStatus.PARTIALLY_PAID);
        assertEquals(1,partial.count()); assertEquals(money("50.00"),partial.paidToDate());
        assertEquals(money("50.00"),partial.overdueOutstanding());
        assertEquals(1,partial.overdueCount());
        assertEquals(money("100.00"),bucket(result,Direction.INFLOW,PaymentStatus.UNPAID).outstanding());
        assertEquals(money("80.00"),bucket(result,Direction.OUTFLOW,PaymentStatus.PAID).paidToDate());
    }

    @Test
    void dueTodayIsNotOverdueAndDueYesterdayIsOverdueOnlyWhileOutstanding() {
        Invoice invoice = invoice(1,"100.00",PaymentStatus.UNPAID);
        assertFalse(summary(start,List.of(invoice),List.of()).invoices().get(0).overdue());
        assertTrue(summary(start.plusDays(1),List.of(invoice),List.of()).invoices().get(0).overdue());
        var paid = summary(start.plusDays(1),List.of(invoice),List.of(movement(1,1,Direction.INFLOW,"100.00",1L)));
        assertFalse(paid.invoices().get(0).overdue());
        assertEquals(PaymentStatus.PAID,paid.invoices().get(0).effectiveStatus());
    }

    @Test
    void partialPaymentCutoffIncludesAsOfDayAndExcludesLaterPayments() {
        var invoices = List.of(invoice(1,"100.00",PaymentStatus.PAID));
        var payments = List.of(movement(1,0,Direction.INFLOW,"40.00",1L),movement(2,1,Direction.INFLOW,"60.00",1L));
        assertEquals(money("60.00"),summary(start,invoices,payments).invoices().get(0).outstanding());
        assertEquals(PaymentStatus.PAID,summary(start.plusDays(1),invoices,payments).invoices().get(0).effectiveStatus());
    }

    @Test
    void emptyInvoiceSummaryHasSixZeroBuckets() {
        var report = summary(start,List.of(),List.of());
        assertTrue(report.invoices().isEmpty());
        assertEquals(6,report.buckets().size());
        for (var bucket : report.buckets()) { assertEquals(0,bucket.count()); assertEquals(money("0.00"),bucket.outstanding()); }
    }

    @Test
    void rejectsInvalidInvoiceInputsRatherThanGuessing() {
        Invoice good = invoice(1,"100.00",PaymentStatus.UNPAID);
        assertThrows(IllegalArgumentException.class, () -> summary(null,List.of(good),List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(start,List.of(good,good),List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(start,List.of(invoice(1,Direction.INFLOW,"100.00",PaymentStatus.UNPAID,start,start.minusDays(1))),List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(start,List.of(invoice(1,"0.00",PaymentStatus.UNPAID)),List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(start,List.of(good),List.of(movement(1,0,Direction.INFLOW,"1.00",99L))));
        assertThrows(IllegalArgumentException.class, () -> summary(start,List.of(good),List.of(movement(1,0,Direction.OUTFLOW,"1.00",1L))));
    }

    @Test
    void aggregateInvoiceOverflowIsExplicit() {
        assertThrows(IllegalArgumentException.class, () -> summary(start,List.of(invoice(1,Money.MAX.toPlainString(),PaymentStatus.UNPAID),invoice(2,"0.01",PaymentStatus.UNPAID)),List.of()));
    }

    @Test
    void invalidServiceConfigurationFailsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new FinancialReportService(null,calculator,money("-1.00"),10));
        assertThrows(IllegalArgumentException.class, () -> new FinancialReportService(null,calculator,money("0.00"),0));
    }
}
