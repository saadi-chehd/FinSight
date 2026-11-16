package com.finsight.financial;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static com.finsight.financial.FinancialModels.*;

@Repository
public class FinancialReadRepository {
    private final JdbcClient jdbc;
    public FinancialReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<Movement> MOVEMENT = (rs, row) -> new Movement(
        rs.getLong("id"), rs.getObject("invoice_id", Long.class), rs.getObject("booked_date", LocalDate.class),
        rs.getString("currency"), Direction.valueOf(rs.getString("direction")), rs.getBigDecimal("amount"));

    public String currency(long smeId) {
        return jdbc.sql("SELECT currency FROM smes WHERE id=:sme").param("sme", smeId)
            .query(String.class).optional().orElseThrow(() -> new IllegalArgumentException("Unknown SME"));
    }

    public List<Movement> movements(long smeId, LocalDate start, LocalDate end) {
        return jdbc.sql("""
            SELECT id, invoice_id, booked_date, currency, direction, amount FROM financial_transactions
            WHERE sme_id=:sme AND booked_date BETWEEN :start AND :end ORDER BY booked_date, id
            """).param("sme", smeId).param("start", start).param("end", end).query(MOVEMENT).list();
    }

    public List<Invoice> invoices(long smeId, LocalDate asOf) {
        return jdbc.sql("SELECT * FROM invoices WHERE sme_id=:sme AND issue_date <= :asOf ORDER BY id")
            .param("sme", smeId).param("asOf", asOf).query((rs, row) -> new Invoice(
                rs.getLong("id"), rs.getString("reference"), rs.getString("currency"), Direction.valueOf(rs.getString("direction")),
                rs.getBigDecimal("amount"), rs.getObject("issue_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                PaymentStatus.valueOf(rs.getString("payment_status")))).list();
    }

    public List<Movement> invoicePayments(long smeId, LocalDate asOf) {
        return jdbc.sql("""
            SELECT t.id, t.invoice_id, t.booked_date, t.currency, t.direction, t.amount
            FROM financial_transactions t JOIN invoices i ON i.id=t.invoice_id AND i.sme_id=t.sme_id
            WHERE t.sme_id=:sme AND t.booked_date <= :asOf AND i.issue_date <= :asOf ORDER BY t.id
            """).param("sme", smeId).param("asOf", asOf).query(MOVEMENT).list();
    }
}
