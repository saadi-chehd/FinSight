package com.finsight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Small isolated test fixtures only; no application dataset is generated. */
class FinancialDatabaseTests extends PostgresTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired FinancialRecordRepository records;
    long sme;
    long other;

    @BeforeEach
    void fixtures() {
        sme = records.createSme("Persistence test fixture", "USD");
        other = records.createSme("Other ownership test fixture", "EUR");
    }

    @AfterEach
    void removeFixtures() {
        for (String table : List.of("scenario_inputs", "financial_transactions", "scenarios", "invoices")) {
            jdbc.update("DELETE FROM " + table + " WHERE sme_id IN (?, ?)", sme, other);
        }
        jdbc.update("DELETE FROM smes WHERE id IN (?, ?)", sme, other);
    }

    long invoice() {
        return jdbc.queryForObject("""
            INSERT INTO invoices (sme_id, currency, reference, direction, amount, issue_date, due_date)
            VALUES (?, 'USD', 'TEST-1', 'INFLOW', 100.00, DATE '2026-01-01', DATE '2026-01-31') RETURNING id
            """, Long.class, sme);
    }

    long scenario(String kind) {
        return jdbc.queryForObject("INSERT INTO scenarios (sme_id, name, kind) VALUES (?, 'Test assumption', ?) RETURNING id",
            Long.class, sme, kind);
    }

    @Test
    void usesSeparateDatabaseAndNonsuperuser() {
        assertEquals("finsight_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("finsight_test_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class));
        assertFalse(jdbc.queryForObject("SELECT has_database_privilege(current_user, 'finsight', 'CONNECT')", Boolean.class));
    }

    @Test
    void storesExactMoneyAndDateThroughJavaPersistence() {
        long id = records.recordTransaction(sme, "USD", LocalDate.of(2026, 1, 15), "INFLOW", "CUSTOMER_RECEIPT", new BigDecimal("123.45"));
        assertEquals(new BigDecimal("123.45"), records.readTransactionAmount(sme, id));
        assertEquals(LocalDate.of(2026, 1, 15), jdbc.queryForObject("SELECT booked_date FROM financial_transactions WHERE id=?", LocalDate.class, id));
        assertNotNull(jdbc.queryForObject("SELECT created_at FROM financial_transactions WHERE id=?", java.time.OffsetDateTime.class, id));
        assertThrows(org.springframework.dao.EmptyResultDataAccessException.class, () -> records.readTransactionAmount(other, id));
    }

    @ParameterizedTest
    @ValueSource(strings={"0", "-1.00", "1.001", "100000000000000000.00"})
    void rejectsInvalidMoneyAtJavaBoundary(String amount) {
        assertThrows(IllegalArgumentException.class, () -> records.recordTransaction(sme, "USD", LocalDate.of(2026, 1, 1), "INFLOW", "OTHER", new BigDecimal(amount)));
    }

    @ParameterizedTest
    @ValueSource(strings={"0", "-1", "NaN", "Infinity", "100000000000000000.00"})
    void rejectsInvalidMoneyInDatabase(String amount) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO financial_transactions (sme_id, currency, booked_date, direction, transaction_type, amount)
            VALUES (?, 'USD', DATE '2026-01-01', 'INFLOW', 'OTHER', ?::numeric)
            """, sme, amount));
    }

    @Test
    void rejectsMissingAndUnsupportedSmeFields() {
        assertThrows(DataIntegrityViolationException.class, () -> records.createSme(" ", "USD"));
        assertThrows(DataIntegrityViolationException.class, () -> records.createSme("Test", "JPY"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO smes(name,currency) VALUES (NULL,'USD')"));
    }

    @Test
    void rejectsCurrencyDirectionAndTypeMismatches() {
        assertThrows(DataIntegrityViolationException.class, () -> records.recordTransaction(sme, "EUR", LocalDate.of(2026,1,1), "INFLOW", "OTHER", new BigDecimal("1.00")));
        assertThrows(DataIntegrityViolationException.class, () -> records.recordTransaction(sme, "USD", LocalDate.of(2026,1,1), "INFLOW", "PAYROLL", new BigDecimal("1.00")));
        assertThrows(DataIntegrityViolationException.class, () -> records.recordTransaction(sme, "USD", LocalDate.of(2026,1,1), "SIDEWAYS", "OTHER", new BigDecimal("1.00")));
        assertThrows(DataIntegrityViolationException.class, () -> records.recordTransaction(sme, "USD", LocalDate.of(2026,1,1), "INFLOW", "UNKNOWN", new BigDecimal("1.00")));
    }

    @Test
    void recordsInvoiceStatusAndMultiplePaymentLinksWithoutCalculatingStatus() {
        long invoice = invoice();
        assertEquals("UNPAID", jdbc.queryForObject("SELECT payment_status FROM invoices WHERE id=?", String.class, invoice));
        jdbc.update("UPDATE invoices SET payment_status='PARTIALLY_PAID' WHERE id=?", invoice);
        for (String amount : List.of("20.00", "30.00")) {
            jdbc.update("""
                INSERT INTO financial_transactions(sme_id,currency,invoice_id,booked_date,direction,transaction_type,amount)
                VALUES (?, 'USD', ?, DATE '2026-01-15', 'INFLOW', 'CUSTOMER_RECEIPT', ?::numeric)
                """, sme, invoice, amount);
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM financial_transactions WHERE invoice_id=?", Integer.class, invoice));
        assertEquals("PARTIALLY_PAID", jdbc.queryForObject("SELECT payment_status FROM invoices WHERE id=?", String.class, invoice));
    }

    @Test
    void rejectsInvalidInvoiceDatesAmountsStatusesAndDuplicateReferences() {
        long id = invoice();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE invoices SET due_date=DATE '2025-12-01' WHERE id=?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE invoices SET due_date='infinity'::date WHERE id=?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE invoices SET payment_status='OVERDUE' WHERE id=?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE invoices SET amount='NaN'::numeric WHERE id=?", id));
        assertThrows(DataIntegrityViolationException.class, this::invoice);
    }

    @Test
    void rejectsCrossSmeInvoiceLinksAndDirectionMismatch() {
        long id = invoice();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO financial_transactions(sme_id,currency,invoice_id,booked_date,direction,transaction_type,amount)
            VALUES (?, 'EUR', ?, DATE '2026-01-15','INFLOW','OTHER',10)
            """, other, id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO financial_transactions(sme_id,currency,invoice_id,booked_date,direction,transaction_type,amount)
            VALUES (?, 'USD', ?, DATE '2026-01-15','OUTFLOW','OTHER',10)
            """, sme, id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM smes WHERE id=?", sme));
    }

    @Test
    void storesTypedScenarioInputsSeparateFromActualTransactions() {
        long invoice = invoice();
        long late = scenario("LATE_PAYMENT");
        long contract = scenario("NEW_CONTRACT");
        long baseline = scenario("BASELINE");
        jdbc.update("""
            INSERT INTO scenario_inputs(scenario_id,sme_id,currency,kind,invoice_id,delay_days)
            VALUES (?,?,'USD','LATE_PAYMENT',?,14)
            """, late,sme,invoice);
        jdbc.update("""
            INSERT INTO scenario_inputs(scenario_id,sme_id,currency,kind,amount,expected_date)
            VALUES (?,?,'USD','NEW_CONTRACT',200.50,DATE '2026-02-01')
            """, contract,sme);
        assertEquals(14,jdbc.queryForObject("SELECT delay_days FROM scenario_inputs WHERE scenario_id=?",Integer.class,late));
        assertEquals(new BigDecimal("200.50"),jdbc.queryForObject("SELECT amount FROM scenario_inputs WHERE scenario_id=?",BigDecimal.class,contract));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM scenario_inputs WHERE scenario_id=?",Integer.class,baseline));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM financial_transactions WHERE sme_id=?",Integer.class,sme));
    }

    @Test
    void rejectsInvalidScenarioInputShapesAndOwnership() {
        long late = scenario("LATE_PAYMENT");
        long invoice = invoice();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO scenario_inputs(scenario_id,sme_id,currency,kind,invoice_id,delay_days)
            VALUES (?,?,'USD','LATE_PAYMENT',?,0)
            """,late,sme,invoice));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO scenario_inputs(scenario_id,sme_id,currency,kind,amount,expected_date)
            VALUES (?,?,'USD','NEW_CONTRACT',20,DATE '2026-02-01')
            """,late,sme));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO scenario_inputs(scenario_id,sme_id,currency,kind,invoice_id,delay_days)
            VALUES (?,?,'EUR','LATE_PAYMENT',?,14)
            """,late,other,invoice));
        long contract = scenario("NEW_CONTRACT");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO scenario_inputs(scenario_id,sme_id,currency,kind) VALUES (?,?,'USD','NEW_CONTRACT')
            """,contract,sme));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO scenario_inputs(scenario_id,sme_id,currency,kind,amount,expected_date)
            VALUES (?,?,'USD','NEW_CONTRACT',-1,DATE '2026-02-01')
            """,contract,sme));
    }
}
