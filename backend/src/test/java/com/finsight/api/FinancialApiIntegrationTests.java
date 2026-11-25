package com.finsight.api;

import com.finsight.FinancialRecordRepository;
import com.finsight.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "financial.liquidity-warning-threshold=10.00",
    "financial.max-report-days=31"
})
class FinancialApiIntegrationTests extends PostgresTestSupport {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FinancialRecordRepository records;
    @Autowired JdbcTemplate jdbc;

    long sme;
    long otherSme;
    final LocalDate start = LocalDate.of(2026, 2, 10);

    @BeforeEach
    void fixtures() {
        sme = records.createSme("API Fixture SME", "USD");
        otherSme = records.createSme("API Other SME", "EUR");
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("scenario_inputs", "financial_transactions", "scenarios", "invoices")) {
            jdbc.update("DELETE FROM " + table + " WHERE sme_id IN (?, ?)", sme, otherSme);
        }
        jdbc.update("DELETE FROM smes WHERE id IN (?, ?)", sme, otherSme);
    }

    long insertInvoice(long owner, String currency, String reference, String direction,
                       String amount, String status, LocalDate issueDate, LocalDate dueDate) {
        return jdbc.queryForObject("""
            INSERT INTO invoices(sme_id, currency, reference, direction, amount, payment_status, issue_date, due_date)
            VALUES (?, ?, ?, ?, ?::numeric, ?, ?, ?) RETURNING id
            """, Long.class, owner, currency, reference, direction, amount, status, issueDate, dueDate);
    }

    long insertLinkedTransaction(long owner, String currency, long invoiceId, LocalDate bookedDate,
                                 String direction, String type, String amount, String description) {
        return jdbc.queryForObject("""
            INSERT INTO financial_transactions(sme_id, currency, invoice_id, booked_date, direction, transaction_type, amount, description)
            VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?) RETURNING id
            """, Long.class, owner, currency, invoiceId, bookedDate, direction, type, amount, description);
    }

    @Test
    void listsSmesOrderedByIdWithIsoCreatedAt() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/smes"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.isArray());

        long previousId = Long.MIN_VALUE;
        JsonNode foundPrimary = null;
        JsonNode foundOther = null;
        for (JsonNode item : root) {
            long id = item.get("id").asLong();
            assertTrue(id > previousId, "SMEs must be ordered by ascending id");
            previousId = id;
            if (id == sme) {
                foundPrimary = item;
            } else if (id == otherSme) {
                foundOther = item;
            }
        }

        assertNotNull(foundPrimary);
        assertEquals("API Fixture SME", foundPrimary.get("name").asString());
        assertEquals("USD", foundPrimary.get("currency").asString());
        String primaryCreatedAt = foundPrimary.get("createdAt").asString();
        assertDoesNotThrow(() -> OffsetDateTime.parse(primaryCreatedAt));

        assertNotNull(foundOther);
        assertEquals("API Other SME", foundOther.get("name").asString());
        assertEquals("EUR", foundOther.get("currency").asString());
        String otherCreatedAt = foundOther.get("createdAt").asString();
        assertDoesNotThrow(() -> OffsetDateTime.parse(otherCreatedAt));
    }

    @Test
    void returnsDailyCashFlowWithExactTwoDecimalStringsAndWarnings() throws Exception {
        records.recordTransaction(sme, "USD", start.minusDays(1), "INFLOW", "OTHER", new BigDecimal("999.00"));
        records.recordTransaction(sme, "USD", start, "INFLOW", "OTHER", new BigDecimal("20.10"));
        records.recordTransaction(sme, "USD", start, "INFLOW", "OTHER", new BigDecimal("0.20"));
        records.recordTransaction(sme, "USD", start, "OUTFLOW", "OTHER", new BigDecimal("5.00"));
        records.recordTransaction(sme, "USD", start.plusDays(2), "OUTFLOW", "OTHER", new BigDecimal("120.00"));
        records.recordTransaction(sme, "USD", start.plusDays(3), "INFLOW", "OTHER", new BigDecimal("999.00"));
        records.recordTransaction(otherSme, "EUR", start, "INFLOW", "OTHER", new BigDecimal("999.00"));

        mockMvc.perform(get("/api/smes/{smeId}/cash-flow", sme)
                .param("openingDate", start.toString())
                .param("openingAmount", "100.00")
                .param("currency", "USD")
                .param("endDate", start.plusDays(2).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smeId").value(sme))
            .andExpect(jsonPath("$.opening.date").value(start.toString()))
            .andExpect(jsonPath("$.opening.currency").value("USD"))
            .andExpect(jsonPath("$.opening.amount").value("100.00"))
            .andExpect(jsonPath("$.endDate").value(start.plusDays(2).toString()))
            .andExpect(jsonPath("$.warningThreshold").value("10.00"))
            .andExpect(jsonPath("$.days", hasSize(3)))
            .andExpect(jsonPath("$.days[0].date").value(start.toString()))
            .andExpect(jsonPath("$.days[0].openingBalance").value("100.00"))
            .andExpect(jsonPath("$.days[0].inflow").value("20.30"))
            .andExpect(jsonPath("$.days[0].outflow").value("5.00"))
            .andExpect(jsonPath("$.days[0].netMovement").value("15.30"))
            .andExpect(jsonPath("$.days[0].closingBalance").value("115.30"))
            .andExpect(jsonPath("$.days[1].openingBalance").value("115.30"))
            .andExpect(jsonPath("$.days[1].inflow").value("0.00"))
            .andExpect(jsonPath("$.days[1].outflow").value("0.00"))
            .andExpect(jsonPath("$.days[1].netMovement").value("0.00"))
            .andExpect(jsonPath("$.days[1].closingBalance").value("115.30"))
            .andExpect(jsonPath("$.days[2].closingBalance").value("-4.70"))
            .andExpect(jsonPath("$.warnings", hasSize(1)))
            .andExpect(jsonPath("$.warnings[0].date").value(start.plusDays(2).toString()))
            .andExpect(jsonPath("$.warnings[0].kind").value("POTENTIAL_CASH_SHORTFALL"))
            .andExpect(jsonPath("$.warnings[0].closingBalance").value("-4.70"))
            .andExpect(jsonPath("$.warnings[0].threshold").value("10.00"))
            .andExpect(jsonPath("$.warnings[0].gapToThreshold").value("14.70"));

        mockMvc.perform(get("/api/smes/{smeId}/cash-flow", otherSme)
                .param("openingDate", start.plusDays(1).toString())
                .param("openingAmount", "9.99")
                .param("currency", "EUR")
                .param("endDate", start.plusDays(1).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.warnings", hasSize(1)))
            .andExpect(jsonPath("$.warnings[0].kind").value("BELOW_CONFIGURED_BUFFER"))
            .andExpect(jsonPath("$.warnings[0].closingBalance").value("9.99"))
            .andExpect(jsonPath("$.warnings[0].gapToThreshold").value("0.01"));

        mockMvc.perform(get("/api/smes/{smeId}/cash-flow", otherSme)
                .param("openingDate", start.plusDays(1).toString())
                .param("openingAmount", "10.00")
                .param("currency", "EUR")
                .param("endDate", start.plusDays(1).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    @Test
    void returnsInvoiceSummaryWithEffectiveStatusesAndBuckets() throws Exception {
        long unpaidId = insertInvoice(sme, "USD", "INV-U", "INFLOW", "100.00", "UNPAID", start.minusDays(5), start.minusDays(1));
        long partialId = insertInvoice(sme, "USD", "INV-P", "INFLOW", "100.00", "UNPAID", start.minusDays(5), start.minusDays(1));
        long paidId = insertInvoice(sme, "USD", "INV-F", "OUTFLOW", "80.00", "PAID", start.minusDays(5), start.minusDays(1));
        insertInvoice(sme, "USD", "INV-C", "INFLOW", "25.00", "CANCELLED", start.minusDays(3), start.minusDays(1));
        insertInvoice(sme, "USD", "INV-FUT", "INFLOW", "900.00", "UNPAID", start.plusDays(1), start.plusDays(2));
        insertInvoice(otherSme, "EUR", "INV-OTHER", "INFLOW", "900.00", "UNPAID", start.minusDays(1), start);

        insertLinkedTransaction(sme, "USD", partialId, start.minusDays(1), "INFLOW", "CUSTOMER_RECEIPT", "30.00", "Part 1");
        insertLinkedTransaction(sme, "USD", partialId, start, "INFLOW", "CUSTOMER_RECEIPT", "20.00", "Part 2");
        insertLinkedTransaction(sme, "USD", partialId, start.plusDays(1), "INFLOW", "CUSTOMER_RECEIPT", "50.00", "After as-of");
        insertLinkedTransaction(sme, "USD", paidId, start, "OUTFLOW", "SUPPLIER_PAYMENT", "80.00", "Paid bill");

        mockMvc.perform(get("/api/smes/{smeId}/invoices/summary", sme)
                .param("asOfDate", start.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smeId").value(sme))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.asOfDate").value(start.toString()))
            .andExpect(jsonPath("$.buckets", hasSize(6)))
            .andExpect(jsonPath("$.invoices", hasSize(4)))
            .andExpect(jsonPath("$.invoices[0].id").value(unpaidId))
            .andExpect(jsonPath("$.invoices[0].recordedStatus").value("UNPAID"))
            .andExpect(jsonPath("$.invoices[0].effectiveStatus").value("UNPAID"))
            .andExpect(jsonPath("$.invoices[0].statusMismatch").value(false))
            .andExpect(jsonPath("$.invoices[0].overdue").value(true))
            .andExpect(jsonPath("$.invoices[0].faceAmount").value("100.00"))
            .andExpect(jsonPath("$.invoices[0].paidToDate").value("0.00"))
            .andExpect(jsonPath("$.invoices[0].outstanding").value("100.00"))
            .andExpect(jsonPath("$.invoices[1].id").value(partialId))
            .andExpect(jsonPath("$.invoices[1].recordedStatus").value("UNPAID"))
            .andExpect(jsonPath("$.invoices[1].effectiveStatus").value("PARTIALLY_PAID"))
            .andExpect(jsonPath("$.invoices[1].statusMismatch").value(true))
            .andExpect(jsonPath("$.invoices[1].overdue").value(true))
            .andExpect(jsonPath("$.invoices[1].dueDate").value(start.minusDays(1).toString()))
            .andExpect(jsonPath("$.invoices[1].paidToDate").value("50.00"))
            .andExpect(jsonPath("$.invoices[1].outstanding").value("50.00"))
            .andExpect(jsonPath("$.invoices[1].overpayment").value("0.00"))
            .andExpect(jsonPath("$.invoices[2].id").value(paidId))
            .andExpect(jsonPath("$.invoices[2].effectiveStatus").value("PAID"))
            .andExpect(jsonPath("$.invoices[2].overdue").value(false))
            .andExpect(jsonPath("$.invoices[3].effectiveStatus").value("CANCELLED"))
            .andExpect(jsonPath("$.invoices[3].outstanding").value("0.00"))
            .andExpect(jsonPath("$.invoices[3].overdue").value(false));
    }

    @Test
    void listsTransactionsOrderedAndFilteredByDateRange() throws Exception {
        long invId = insertInvoice(sme, "USD", "INV-TX", "INFLOW", "200.00", "UNPAID", start.minusDays(5), start.plusDays(10));
        long tx1 = insertLinkedTransaction(sme, "USD", invId, start.minusDays(1), "INFLOW", "CUSTOMER_RECEIPT", "25.00", "Day -1");
        long tx2 = records.recordTransaction(sme, "USD", start, "OUTFLOW", "OTHER", new BigDecimal("10.50"));
        long tx3 = records.recordTransaction(sme, "USD", start, "INFLOW", "OTHER", new BigDecimal("115.30"));
        long tx4 = records.recordTransaction(sme, "USD", start.plusDays(2), "OUTFLOW", "PAYROLL", new BigDecimal("40.00"));
        records.recordTransaction(otherSme, "EUR", start, "INFLOW", "OTHER", new BigDecimal("999.00"));

        mockMvc.perform(get("/api/smes/{smeId}/transactions", sme))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smeId").value(sme))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.from").value(nullValue()))
            .andExpect(jsonPath("$.to").value(nullValue()))
            .andExpect(jsonPath("$.transactions", hasSize(4)))
            .andExpect(jsonPath("$.transactions[0].id").value(tx1))
            .andExpect(jsonPath("$.transactions[0].invoiceId").value(invId))
            .andExpect(jsonPath("$.transactions[0].bookedDate").value(start.minusDays(1).toString()))
            .andExpect(jsonPath("$.transactions[0].currency").value("USD"))
            .andExpect(jsonPath("$.transactions[0].direction").value("INFLOW"))
            .andExpect(jsonPath("$.transactions[0].transactionType").value("CUSTOMER_RECEIPT"))
            .andExpect(jsonPath("$.transactions[0].amount").value("25.00"))
            .andExpect(jsonPath("$.transactions[0].description").value("Day -1"))
            .andExpect(jsonPath("$.transactions[1].id").value(tx2))
            .andExpect(jsonPath("$.transactions[1].invoiceId").value(nullValue()))
            .andExpect(jsonPath("$.transactions[1].amount").value("10.50"))
            .andExpect(jsonPath("$.transactions[2].id").value(tx3))
            .andExpect(jsonPath("$.transactions[2].amount").value("115.30"))
            .andExpect(jsonPath("$.transactions[3].id").value(tx4))
            .andExpect(jsonPath("$.transactions[3].transactionType").value("PAYROLL"))
            .andExpect(jsonPath("$.transactions[3].amount").value("40.00"));

        mockMvc.perform(get("/api/smes/{smeId}/transactions", sme)
                .param("from", start.toString())
                .param("to", start.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.from").value(start.toString()))
            .andExpect(jsonPath("$.to").value(start.toString()))
            .andExpect(jsonPath("$.transactions", hasSize(2)))
            .andExpect(jsonPath("$.transactions[0].id").value(tx2))
            .andExpect(jsonPath("$.transactions[1].id").value(tx3));

        mockMvc.perform(get("/api/smes/{smeId}/transactions", sme)
                .param("from", start.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactions", hasSize(3)));

        mockMvc.perform(get("/api/smes/{smeId}/transactions", sme)
                .param("to", start.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactions", hasSize(3)));
    }

    @Test
    void rejectsInvalidInputsAndUnknownSmeWithCleanApiErrorJson() throws Exception {
        // Missing required parameters -> 400
        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/cash-flow", sme)
                    .param("openingDate", start.toString())
                    .param("currency", "USD")
                    .param("endDate", start.toString()))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/" + sme + "/cash-flow"
        );

        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/invoices/summary", sme))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/" + sme + "/invoices/summary"
        );

        // Invalid / fractional openingAmount -> 400
        for (String badAmount : List.of("abc", "10.001", " ")) {
            assertApiError(
                mockMvc.perform(get("/api/smes/{smeId}/cash-flow", sme)
                        .param("openingDate", start.toString())
                        .param("openingAmount", badAmount)
                        .param("currency", "USD")
                        .param("endDate", start.toString()))
                    .andExpect(status().isBadRequest())
                    .andReturn(),
                400, "BAD_REQUEST", "/api/smes/" + sme + "/cash-flow"
            );
        }

        // Currency mismatch and unsupported currency -> 400
        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/cash-flow", sme)
                    .param("openingDate", start.toString())
                    .param("openingAmount", "100.00")
                    .param("currency", "EUR")
                    .param("endDate", start.toString()))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/" + sme + "/cash-flow"
        );

        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/cash-flow", sme)
                    .param("openingDate", start.toString())
                    .param("openingAmount", "100.00")
                    .param("currency", "JPY")
                    .param("endDate", start.toString()))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/" + sme + "/cash-flow"
        );

        // Reversed dates & oversized range -> 400
        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/cash-flow", sme)
                    .param("openingDate", start.toString())
                    .param("openingAmount", "100.00")
                    .param("currency", "USD")
                    .param("endDate", start.minusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/" + sme + "/cash-flow"
        );

        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/cash-flow", sme)
                    .param("openingDate", start.toString())
                    .param("openingAmount", "100.00")
                    .param("currency", "USD")
                    .param("endDate", start.plusDays(31).toString()))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/" + sme + "/cash-flow"
        );

        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/transactions", sme)
                    .param("from", start.toString())
                    .param("to", start.minusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/" + sme + "/transactions"
        );

        // Non-positive SME ID -> 400
        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/transactions", 0))
                .andExpect(status().isBadRequest())
                .andReturn(),
            400, "BAD_REQUEST", "/api/smes/0/transactions"
        );

        // Unknown SME -> 404
        long missingSme = 999_999_999L;
        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/cash-flow", missingSme)
                    .param("openingDate", start.toString())
                    .param("openingAmount", "100.00")
                    .param("currency", "USD")
                    .param("endDate", start.toString()))
                .andExpect(status().isNotFound())
                .andReturn(),
            404, "NOT_FOUND", "/api/smes/" + missingSme + "/cash-flow"
        );

        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/invoices/summary", missingSme)
                    .param("asOfDate", start.toString()))
                .andExpect(status().isNotFound())
                .andReturn(),
            404, "NOT_FOUND", "/api/smes/" + missingSme + "/invoices/summary"
        );

        assertApiError(
            mockMvc.perform(get("/api/smes/{smeId}/transactions", missingSme))
                .andExpect(status().isNotFound())
                .andReturn(),
            404, "NOT_FOUND", "/api/smes/" + missingSme + "/transactions"
        );
    }

    private void assertApiError(MvcResult result, int expectedStatus, String expectedError, String expectedPath)
            throws Exception {
        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(expectedStatus, json.get("status").asInt());
        assertEquals(expectedError, json.get("error").asString());
        assertFalse(json.get("message").asString("").isBlank());
        assertEquals(expectedPath, json.get("path").asString());
        assertFalse(body.contains("SELECT"));
        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("org.springframework"));
    }
}
