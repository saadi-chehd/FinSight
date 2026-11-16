package com.finsight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Minimal persistence boundary, without calculations, simulation, or public CRUD APIs. */
@Repository
public class FinancialRecordRepository {
    private final JdbcClient jdbc;

    public FinancialRecordRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long createSme(String name, String currency) {
        return jdbc.sql("INSERT INTO smes (name, currency) VALUES (:name, :currency) RETURNING id")
            .param("name", name).param("currency", currency).query(Long.class).single();
    }

    public long recordTransaction(long smeId, String currency, LocalDate date,
                                  String direction, String type, BigDecimal amount) {
        // Validate precision before PostgreSQL NUMERIC(19,2) could silently round it.
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        final BigDecimal exact;
        try { exact = amount.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Amount must use whole minor units", e); }
        if (exact.precision() > 19) throw new IllegalArgumentException("Amount exceeds NUMERIC(19,2)");
        return jdbc.sql("""
            INSERT INTO financial_transactions
                (sme_id, currency, booked_date, direction, transaction_type, amount)
            VALUES (:sme, :currency, :date, :direction, :type, :amount) RETURNING id
            """)
            .param("sme", smeId).param("currency", currency).param("date", date)
            .param("direction", direction).param("type", type).param("amount", exact)
            .query(Long.class).single();
    }

    public BigDecimal readTransactionAmount(long smeId, long transactionId) {
        return jdbc.sql("SELECT amount FROM financial_transactions WHERE sme_id = :sme AND id = :id")
            .param("sme", smeId).param("id", transactionId).query(BigDecimal.class).single();
    }
}
