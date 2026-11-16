package com.finsight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import com.finsight.financial.*;
import com.finsight.synthetic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import static com.finsight.financial.FinancialModels.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties={"financial.liquidity-warning-threshold=0.00","financial.max-report-days=3660"})
class SyntheticDataLoadingTests extends PostgresTestSupport {
    @Autowired DataSource source;
    @Autowired FinancialReportService reports;
    @Autowired JdbcTemplate jdbc;
    final SyntheticDataset data=SyntheticGenerator.generate(900000001L);
    SyntheticDataLoader loader;
    long unrelated;
    @BeforeEach
    void setup() {
        loader=new SyntheticDataLoader(source,reports);
        unrelated=jdbc.queryForObject("INSERT INTO smes(name,currency) VALUES ('Unrelated seeder test fixture','EUR') RETURNING id",Long.class);
    }
    @AfterEach
    void cleanupOnlyOwnedFixtures() {
        for (var c:data.companies()) {
            for (long id:jdbc.queryForList("SELECT id FROM smes WHERE name=?",Long.class,c.name())) {
                jdbc.update("DELETE FROM financial_transactions WHERE sme_id=?",id);
                jdbc.update("DELETE FROM invoices WHERE sme_id=?",id);
                jdbc.update("DELETE FROM smes WHERE id=?",id);
            }
        }
        jdbc.update("DELETE FROM smes WHERE id=?",unrelated);
    }
    @Test
    void realLoadIsConsistentAndRepeatLoadingDoesNotDuplicateOrTouchUnrelatedData() {
        var first=loader.load(data); assertTrue(first.inserted()); assertEquals(3,first.companyIds().size());
        for (var company:data.companies()) {
            long id=first.companyIds().get(company.key());
            assertEquals(data.invoices().stream().filter(i->i.companyKey().equals(company.key())).count(),jdbc.queryForObject("SELECT count(*) FROM invoices WHERE sme_id=?",Long.class,id));
            assertEquals(data.transactions().stream().filter(t->t.companyKey().equals(company.key())).count(),jdbc.queryForObject("SELECT count(*) FROM financial_transactions WHERE sme_id=?",Long.class,id));
            var summary=reports.invoiceSummary(id,data.end());
            assertFalse(summary.invoices().stream().anyMatch(InvoicePosition::statusMismatch));
            BigDecimal signed=jdbc.queryForObject("SELECT coalesce(sum(CASE direction WHEN 'INFLOW' THEN amount ELSE -amount END),0) FROM financial_transactions WHERE sme_id=?",BigDecimal.class,id);
            assertEquals(0,company.opening().add(signed).compareTo(first.closingBalances().get(company.key())));
        }
        var second=loader.load(data); assertFalse(second.inserted()); assertEquals(first.companyIds(),second.companyIds());
        assertEquals(first.closingBalances(),loader.verify(data).closingBalances());
        assertEquals("EUR",jdbc.queryForObject("SELECT currency FROM smes WHERE id=?",String.class,unrelated));
    }
    @Test
    void detectsChangedLoadedDataWithoutOverwritingIt() {
        var result=loader.load(data); long id=result.companyIds().values().iterator().next();
        long tx=jdbc.queryForObject("SELECT min(id) FROM financial_transactions WHERE sme_id=?",Long.class,id);
        jdbc.update("UPDATE financial_transactions SET amount=amount+0.01 WHERE id=?",tx);
        BigDecimal changed=jdbc.queryForObject("SELECT amount FROM financial_transactions WHERE id=?",BigDecimal.class,tx);
        assertThrows(IllegalStateException.class,()->loader.load(data));
        assertThrows(IllegalStateException.class,()->loader.verify(data));
        assertEquals(changed,jdbc.queryForObject("SELECT amount FROM financial_transactions WHERE id=?",BigDecimal.class,tx));
    }
    @Test
    void refusesPartialNamespaceWithoutFillingItOrDeletingExistingCompany() {
        var company=data.companies().get(0);
        long id=jdbc.queryForObject("INSERT INTO smes(name,currency) VALUES (?,?) RETURNING id",Long.class,company.name(),company.currency());
        assertThrows(IllegalStateException.class,()->loader.load(data));
        assertEquals(company.name(),jdbc.queryForObject("SELECT name FROM smes WHERE id=?",String.class,id));
        for (var other:data.companies().subList(1,3)) assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM smes WHERE name=?",Integer.class,other.name()));
    }
    @Test
    void verificationFailureAfterInsertsRollsBackEntireDataset() {
        var failingReports=new FinancialReportService(new FinancialReadRepository(JdbcClient.create(source)),new FinancialCalculator(),new BigDecimal("0.00"),3660) {
            @Override public CashFlowReport dailyCashFlow(long sme,OpeningCash opening,LocalDate end) { throw new IllegalStateException("Injected verification failure"); }
        };
        assertThrows(IllegalStateException.class,()->new SyntheticDataLoader(source,failingReports).load(data));
        for (var company:data.companies()) assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM smes WHERE name=?",Integer.class,company.name()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM smes WHERE id=?",Integer.class,unrelated));
    }
}
