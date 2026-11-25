package com.finsight.api;

import com.finsight.api.FinancialApiModels.SmeSummary;
import com.finsight.api.FinancialApiModels.TransactionView;
import com.finsight.api.FinancialApiModels.TransactionsResponse;
import com.finsight.financial.FinancialModels.Direction;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only JDBC queries for SME listing and transaction retrieval. */
@Repository
public class FinancialApiRepository {
    private final JdbcClient jdbc;

    public FinancialApiRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<SmeSummary> listSmes() {
        return jdbc.sql("""
                SELECT id, name, currency, created_at
                FROM smes
                ORDER BY id
                """)
            .query((rs, row) -> new SmeSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("currency"),
                rs.getObject("created_at", OffsetDateTime.class)
            ))
            .list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TransactionsResponse findTransactions(long smeId, LocalDate from, LocalDate to) {
        String currency = jdbc.sql("SELECT currency FROM smes WHERE id = :smeId")
            .param("smeId", smeId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new ResourceNotFoundException("Unknown SME"));

        StringBuilder sql = new StringBuilder("""
            SELECT id, invoice_id, booked_date, currency, direction, transaction_type, amount, description
            FROM financial_transactions
            WHERE sme_id = :smeId
            """);
        if (from != null) {
            sql.append(" AND booked_date >= :from");
        }
        if (to != null) {
            sql.append(" AND booked_date <= :to");
        }
        sql.append(" ORDER BY booked_date, id");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("smeId", smeId);
        if (from != null) {
            spec = spec.param("from", from);
        }
        if (to != null) {
            spec = spec.param("to", to);
        }

        List<TransactionView> transactions = spec.query((rs, row) -> new TransactionView(
                rs.getLong("id"),
                rs.getObject("invoice_id", Long.class),
                rs.getObject("booked_date", LocalDate.class),
                rs.getString("currency"),
                Direction.valueOf(rs.getString("direction")),
                rs.getString("transaction_type"),
                MoneyCodec.format(rs.getBigDecimal("amount")),
                rs.getString("description")
            ))
            .list();

        return new TransactionsResponse(smeId, currency, from, to, transactions);
    }
}
