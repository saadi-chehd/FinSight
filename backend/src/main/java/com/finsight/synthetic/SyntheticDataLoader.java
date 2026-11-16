package com.finsight.synthetic;

import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.finsight.financial.FinancialCalculator;
import com.finsight.financial.FinancialReportService;
import static com.finsight.financial.FinancialModels.*;

/** Explicitly constructed by CLI/tests only. No startup hook, overwrite, delete, or auto-seeding. */
public final class SyntheticDataLoader {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final FinancialReportService reports;
    public record Result(boolean inserted,Map<String,Long> companyIds,Map<String,BigDecimal> closingBalances) {
        public Result { companyIds=Collections.unmodifiableMap(new TreeMap<>(companyIds)); closingBalances=Collections.unmodifiableMap(new TreeMap<>(closingBalances)); }
    }
    public SyntheticDataLoader(DataSource source,FinancialReportService reports) {
        this.source=source; this.jdbc=new JdbcTemplate(source); this.reports=reports;
    }
    private TransactionTemplate transaction(boolean readOnly) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); tx.setReadOnly(readOnly); return tx;
    }
    public Result load(SyntheticDataset data) {
        data.validate();
        // Acquire a session lock on a dedicated connection BEFORE the transaction's snapshot.
        // Taking an xact lock inside REPEATABLE READ could leave a waiting loader with stale data.
        try (var connection=source.getConnection(); var lock=connection.createStatement()) {
            lock.execute("SELECT pg_advisory_lock(4608817)");
            try {
                return transaction(false).execute(status->{
            Map<String,Long> ids=findCompanies(data);
            boolean inserted=ids.isEmpty();
            if (!inserted && ids.size()!=data.companies().size()) throw new IllegalStateException("Partial synthetic namespace exists; refusing to overwrite");
            if (inserted) {
                for (var c:data.companies()) ids.put(c.key(),jdbc.queryForObject("INSERT INTO smes(name,currency) VALUES (?,?) RETURNING id",Long.class,c.name(),c.currency()));
                Map<String,Long> invoiceIds=new HashMap<>();
                for (var b:data.invoices()) {
                    long id=jdbc.queryForObject("""
                        INSERT INTO invoices(sme_id,currency,reference,direction,amount,issue_date,due_date,payment_status)
                        VALUES (?,?,?,?,?,?,?,?) RETURNING id
                        """,Long.class,ids.get(b.companyKey()),data.currency(b.companyKey()),b.key(),b.direction().name(),b.amount(),b.issued(),b.due(),b.status().name());
                    invoiceIds.put(b.key(),id);
                }
                List<Object[]> rows=new ArrayList<>();
                for (var t:data.transactions()) rows.add(new Object[]{ids.get(t.companyKey()),data.currency(t.companyKey()),
                    t.invoiceKey()==null?null:invoiceIds.get(t.invoiceKey()),t.date(),t.direction().name(),t.type(),t.amount(),t.description()});
                jdbc.batchUpdate("""
                    INSERT INTO financial_transactions(sme_id,currency,invoice_id,booked_date,direction,transaction_type,amount,description)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,rows);
            }
            return verifyRows(data,ids,inserted);
                });
            } finally {
                lock.execute("SELECT pg_advisory_unlock(4608817)");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Synthetic loader connection/lock failed",e);
        }
    }
    public Result verify(SyntheticDataset data) {
        data.validate();
        return transaction(true).execute(status->verifyRows(data,findCompanies(data),false));
    }
    private Map<String,Long> findCompanies(SyntheticDataset data) {
        Map<String,Long> ids=new TreeMap<>();
        for (var c:data.companies()) {
            List<Long> found=jdbc.queryForList("SELECT id FROM smes WHERE name=?",Long.class,c.name());
            if (found.size()>1) throw new IllegalStateException("Duplicate synthetic SME name; refusing ambiguous data");
            if (!found.isEmpty()) ids.put(c.key(),found.get(0));
        }
        return ids;
    }
    private Result verifyRows(SyntheticDataset data,Map<String,Long> ids,boolean inserted) {
        if (ids.size()!=data.companies().size()) throw new IllegalStateException("Synthetic namespace not completely loaded");
        Map<String,BigDecimal> closings=new TreeMap<>();
        FinancialCalculator calculator=new FinancialCalculator();
        for (int index=0;index<data.companies().size();index++) {
            var c=data.companies().get(index); long id=ids.get(c.key());
            if (!c.currency().equals(jdbc.queryForObject("SELECT currency FROM smes WHERE id=?",String.class,id))) throw new IllegalStateException("Existing synthetic SME differs");
            var actualInvoices=jdbc.query("SELECT * FROM invoices WHERE sme_id=? ORDER BY reference",(rs,row)->new SyntheticDataset.Bill(
                rs.getString("reference"),c.key(),Direction.valueOf(rs.getString("direction")),rs.getBigDecimal("amount"),
                rs.getObject("issue_date",java.time.LocalDate.class),rs.getObject("due_date",java.time.LocalDate.class),PaymentStatus.valueOf(rs.getString("payment_status"))),id);
            var expectedInvoices=data.invoices().stream().filter(b->b.companyKey().equals(c.key())).sorted(Comparator.comparing(SyntheticDataset.Bill::key)).toList();
            if (!actualInvoices.equals(expectedInvoices)) throw new IllegalStateException("Existing synthetic invoices differ; no data overwritten");
            var actualCash=jdbc.query("""
                SELECT t.*, i.reference FROM financial_transactions t LEFT JOIN invoices i ON i.id=t.invoice_id
                WHERE t.sme_id=? ORDER BY t.description
                """,(rs,row)->{
                    String description=rs.getString("description");
                    String key=description==null?"":description.split(" ",3).length>1?description.split(" ",3)[1]:"";
                    return new SyntheticDataset.Cash(key,c.key(),rs.getString("reference"),rs.getObject("booked_date",java.time.LocalDate.class),
                        Direction.valueOf(rs.getString("direction")),rs.getString("transaction_type"),rs.getBigDecimal("amount"),description);
                },id);
            var expectedCash=data.transactions().stream().filter(t->t.companyKey().equals(c.key())).sorted(Comparator.comparing(SyntheticDataset.Cash::description)).toList();
            if (!actualCash.equals(expectedCash)) throw new IllegalStateException("Existing synthetic cash movements differ; no data overwritten");
            var opening=new OpeningCash(data.start(),c.currency(),c.opening());
            var actualReport=reports.dailyCashFlow(id,opening,data.end());
            var expectedReport=calculator.cashFlow(index+1L,opening,data.end(),data.movements(c.key()),new BigDecimal("0.00"),3660);
            if (!actualReport.days().equals(expectedReport.days())) throw new IllegalStateException("Database cash-flow result differs from generated ledger");
            BigDecimal signed=expectedCash.stream().map(t->t.direction()==Direction.INFLOW?t.amount():t.amount().negate()).reduce(new BigDecimal("0.00"),BigDecimal::add);
            BigDecimal closing=actualReport.days().get(actualReport.days().size()-1).closingBalance();
            if (closing.compareTo(c.opening().add(signed))!=0) throw new IllegalStateException("Opening plus movements does not reconcile");
            closings.put(c.key(),closing);
            for (var cutoff:List.of(data.start().plusDays(365),data.end())) {
                var actualSummary=reports.invoiceSummary(id,cutoff);
                var expectedSummary=calculator.invoiceSummary(index+1L,c.currency(),cutoff,data.invoiceModels(c.key()),data.movements(c.key()));
                if (!positions(actualSummary).equals(positions(expectedSummary)) || !actualSummary.buckets().equals(expectedSummary.buckets())) throw new IllegalStateException("Database invoice summary differs from generated ledger");
            }
        }
        return new Result(inserted,ids,closings);
    }
    private static List<InvoicePosition> positions(InvoiceSummary summary) {
        return summary.invoices().stream().map(p->new InvoicePosition(0,p.reference(),p.direction(),p.dueDate(),p.recordedStatus(),p.effectiveStatus(),
            p.statusMismatch(),p.faceAmount(),p.paidToDate(),p.outstanding(),p.overpayment(),p.overdue())).sorted(Comparator.comparing(InvoicePosition::reference)).toList();
    }
}
