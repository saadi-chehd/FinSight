package com.finsight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.finsight.financial.FinancialReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import static com.finsight.financial.FinancialModels.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties={"financial.liquidity-warning-threshold=10.00", "financial.max-report-days=31"})
class FinancialEngineIntegrationTests extends PostgresTestSupport {
    @Autowired FinancialRecordRepository records;
    @Autowired FinancialReportService reports;
    @Autowired JdbcTemplate jdbc;
    long sme;
    long other;
    final LocalDate start = LocalDate.of(2026, 1, 10);
    static BigDecimal money(String value) { return new BigDecimal(value); }

    @BeforeEach
    void fixtures() {
        sme=records.createSme("Financial engine fixture","USD");
        other=records.createSme("Financial engine other SME","EUR");
    }
    @AfterEach
    void cleanup() {
        for (String table : List.of("scenario_inputs","financial_transactions","scenarios","invoices")) {
            jdbc.update("DELETE FROM " + table + " WHERE sme_id IN (?,?)",sme,other);
        }
        jdbc.update("DELETE FROM smes WHERE id IN (?,?)",sme,other);
    }
    long invoice(long owner, String currency, String reference, String direction, String amount, String status, LocalDate issued, LocalDate due) {
        return jdbc.queryForObject("""
            INSERT INTO invoices(sme_id,currency,reference,direction,amount,payment_status,issue_date,due_date)
            VALUES (?,?,?,?,?::numeric,?,?,?) RETURNING id
            """,Long.class,owner,currency,reference,direction,amount,status,issued,due);
    }
    void payment(long invoice, LocalDate date, String amount) {
        jdbc.update("""
            INSERT INTO financial_transactions(sme_id,currency,invoice_id,booked_date,direction,transaction_type,amount)
            VALUES (?,'USD',?,?,'INFLOW','CUSTOMER_RECEIPT',?::numeric)
            """,sme,invoice,date,amount);
    }

    @Test
    void readsRecordedMovementsAtBothBoundariesAndCarriesEmptyDay() {
        records.recordTransaction(sme,"USD",start.minusDays(1),"INFLOW","OTHER",money("999.00"));
        records.recordTransaction(sme,"USD",start,"INFLOW","OTHER",money("20.10"));
        records.recordTransaction(sme,"USD",start,"INFLOW","OTHER",money("0.20"));
        records.recordTransaction(sme,"USD",start,"OUTFLOW","OTHER",money("5.00"));
        records.recordTransaction(sme,"USD",start.plusDays(2),"OUTFLOW","OTHER",money("120.00"));
        records.recordTransaction(sme,"USD",start.plusDays(3),"INFLOW","OTHER",money("999.00"));
        records.recordTransaction(other,"EUR",start,"INFLOW","OTHER",money("999.00"));
        var report=reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("100.00")),start.plusDays(2));
        assertEquals(3,report.days().size());
        assertEquals(money("115.30"),report.days().get(0).closingBalance());
        assertEquals(money("115.30"),report.days().get(1).openingBalance());
        assertEquals(money("0.00"),report.days().get(1).inflow());
        assertEquals(money("-4.70"),report.days().get(2).closingBalance());
        assertEquals(WarningKind.POTENTIAL_CASH_SHORTFALL,report.warnings().get(0).kind());
        assertEquals(money("10.00"),report.warningThreshold());
        assertEquals(money("14.70"),report.warnings().get(0).gapToThreshold());
    }

    @Test
    void emptyDatabaseDaysUseExplicitOpeningAndConfiguredBuffer() {
        var warning=reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("9.99")),start);
        assertEquals(WarningKind.BELOW_CONFIGURED_BUFFER,warning.warnings().get(0).kind());
        assertTrue(reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("10.00")),start).warnings().isEmpty());
        assertTrue(reports.invoiceSummary(sme,start).invoices().isEmpty());
    }

    @Test
    void invoiceSummariesUseLinkedPaymentsAndDoNotMutateStoredStatus() {
        long unpaid=invoice(sme,"USD","U","INFLOW","100.00","UNPAID",start.minusDays(5),start.minusDays(1));
        long partial=invoice(sme,"USD","P","INFLOW","100.00","UNPAID",start.minusDays(5),start.minusDays(1));
        long paid=invoice(sme,"USD","F","INFLOW","80.00","PAID",start.minusDays(5),start.minusDays(1));
        payment(partial,start.minusDays(1),"30.00"); payment(partial,start,"20.00"); payment(partial,start.plusDays(1),"50.00");
        payment(paid,start,"80.00");
        invoice(sme,"USD","future","INFLOW","900.00","UNPAID",start.plusDays(1),start.plusDays(2));
        invoice(other,"EUR","other","INFLOW","900.00","UNPAID",start.minusDays(1),start);
        records.recordTransaction(sme,"USD",start,"INFLOW","OTHER",money("200.00"));
        var result=reports.invoiceSummary(sme,start);
        assertEquals(3,result.invoices().size());
        var u=result.invoices().stream().filter(i->i.id()==unpaid).findFirst().orElseThrow();
        var p=result.invoices().stream().filter(i->i.id()==partial).findFirst().orElseThrow();
        var f=result.invoices().stream().filter(i->i.id()==paid).findFirst().orElseThrow();
        assertTrue(u.overdue()); assertEquals(money("100.00"),u.outstanding());
        assertEquals(PaymentStatus.PARTIALLY_PAID,p.effectiveStatus()); assertEquals(money("50.00"),p.outstanding());
        assertTrue(p.overdue()); assertTrue(p.statusMismatch());
        assertEquals(PaymentStatus.PAID,f.effectiveStatus()); assertFalse(f.overdue());
        assertEquals("UNPAID",jdbc.queryForObject("SELECT payment_status FROM invoices WHERE id=?",String.class,partial));
        assertEquals(PaymentStatus.PAID,reports.invoiceSummary(sme,start.plusDays(1)).invoices().stream().filter(i->i.id()==partial).findFirst().orElseThrow().effectiveStatus());
    }

    @Test
    void dueDateComparisonUsesExplicitCalendarDateAndInvoicesAreNotCash() {
        invoice(sme,"USD","today","OUTFLOW","50.00","UNPAID",start.minusDays(2),start);
        assertFalse(reports.invoiceSummary(sme,start).invoices().get(0).overdue());
        assertTrue(reports.invoiceSummary(sme,start.plusDays(1)).invoices().get(0).overdue());
        var cash=reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("100.00")),start.plusDays(1));
        assertEquals(money("100.00"),cash.days().get(1).closingBalance());
    }

    @Test
    void rejectsMissingOpeningUnknownSmeCurrencyAndDateRange() {
        assertThrows(IllegalArgumentException.class,()->reports.dailyCashFlow(sme,null,start));
        assertThrows(IllegalArgumentException.class,()->reports.dailyCashFlow(sme,new OpeningCash(start,"EUR",money("1.00")),start));
        assertThrows(IllegalArgumentException.class,()->reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("1.001")),start));
        assertThrows(IllegalArgumentException.class,()->reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("1.00")),start.minusDays(1)));
        assertThrows(IllegalArgumentException.class,()->reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("1.00")),start.plusDays(31)));
        assertThrows(IllegalArgumentException.class,()->reports.invoiceSummary(Long.MAX_VALUE,start));
        assertThrows(IllegalArgumentException.class,()->reports.invoiceSummary(sme,null));
    }

    @Test
    void reportsOverpaymentAndCancellationWithoutNegativeReceivables() {
        long excess=invoice(sme,"USD","excess","INFLOW","10.00","UNPAID",start.minusDays(2),start.minusDays(1));
        long cancelled=invoice(sme,"USD","cancelled","INFLOW","20.00","CANCELLED",start.minusDays(2),start.minusDays(1));
        payment(excess,start,"12.00");
        var result=reports.invoiceSummary(sme,start);
        var first=result.invoices().stream().filter(i->i.id()==excess).findFirst().orElseThrow();
        assertEquals(money("2.00"),first.overpayment()); assertEquals(money("0.00"),first.outstanding());
        var second=result.invoices().stream().filter(i->i.id()==cancelled).findFirst().orElseThrow();
        assertEquals(PaymentStatus.CANCELLED,second.effectiveStatus()); assertFalse(second.overdue());
        assertEquals(1,result.buckets().stream().mapToLong(InvoiceBucket::count).sum());
    }

    @Test
    void calculatedClosingBeyondNumericRangeFailsWithoutWritingAnything() {
        records.recordTransaction(sme,"USD",start,"INFLOW","OTHER",money("0.01"));
        assertThrows(IllegalArgumentException.class,()->reports.dailyCashFlow(sme,new OpeningCash(start,"USD",money("99999999999999999.99")),start));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM financial_transactions WHERE sme_id=?",Integer.class,sme));
    }
}
