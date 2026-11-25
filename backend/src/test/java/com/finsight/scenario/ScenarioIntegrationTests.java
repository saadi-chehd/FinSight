package com.finsight.scenario;

import com.finsight.FinancialRecordRepository;
import com.finsight.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "financial.liquidity-warning-threshold=100.00",
    "financial.max-report-days=31"
})
class ScenarioIntegrationTests extends PostgresTestSupport {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinancialRecordRepository records;

    @Autowired
    private ObjectMapper objectMapper;

    private long sme;
    private long otherSme;
    private long smeInvoice1;
    private long smeInvoice2;
    private long smeUnpaidInvoice;
    private long otherInvoice;

    private final LocalDate start = LocalDate.of(2026, 2, 10);

    @BeforeEach
    void setUp() {
        sme = records.createSme("Scenario Integration SME", "USD");
        otherSme = records.createSme("Scenario Other SME", "EUR");

        smeInvoice1 = insertInvoice(sme, "USD", "INV-SCEN-1", "INFLOW", "120.50", "PAID",
            start.minusDays(5), start);
        smeInvoice2 = insertInvoice(sme, "USD", "INV-SCEN-2", "INFLOW", "80.00", "PAID",
            start.minusDays(4), start.plusDays(2));
        smeUnpaidInvoice = insertInvoice(sme, "USD", "INV-SCEN-3", "INFLOW", "200.00", "UNPAID",
            start.minusDays(3), start.plusDays(1));
        otherInvoice = insertInvoice(otherSme, "EUR", "INV-OTHER-1", "INFLOW", "90.00", "UNPAID",
            start.minusDays(2), start);

        insertLinkedTx(sme, "USD", smeInvoice1, start, "INFLOW", "CUSTOMER_RECEIPT", "120.50");
        insertLinkedTx(sme, "USD", smeInvoice2, start.plusDays(2), "INFLOW", "CUSTOMER_RECEIPT", "80.00");
        records.recordTransaction(sme, "USD", start.plusDays(1), "OUTFLOW", "PAYROLL", new BigDecimal("150.00"));
        records.recordTransaction(otherSme, "EUR", start, "INFLOW", "OTHER", new BigDecimal("500.00"));
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("scenario_inputs", "financial_transactions", "scenarios", "invoices")) {
            jdbc.update("DELETE FROM " + table + " WHERE sme_id IN (?, ?)", sme, otherSme);
        }
        jdbc.update("DELETE FROM smes WHERE id IN (?, ?)", sme, otherSme);
    }

    private long insertInvoice(
        long owner,
        String currency,
        String reference,
        String direction,
        String amount,
        String status,
        LocalDate issued,
        LocalDate due
    ) {
        return jdbc.queryForObject("""
            INSERT INTO invoices(sme_id, currency, reference, direction, amount, payment_status, issue_date, due_date)
            VALUES (?, ?, ?, ?, ?::numeric, ?, ?, ?)
            RETURNING id
            """, Long.class, owner, currency, reference, direction, amount, status, issued, due);
    }

    private void insertLinkedTx(
        long owner,
        String currency,
        long invoiceId,
        LocalDate bookedDate,
        String direction,
        String type,
        String amount
    ) {
        jdbc.update("""
            INSERT INTO financial_transactions(sme_id, currency, invoice_id, booked_date, direction, transaction_type, amount)
            VALUES (?, ?, ?, ?, ?, ?, ?::numeric)
            """, owner, currency, invoiceId, bookedDate, direction, type, amount);
    }

    @Test
    void createListAndDeleteScenariosAcrossAllKinds() throws Exception {
        long baselineId = createScenarioAndGetId("""
            {"name":"Baseline check","kind":"BASELINE","inputs":[]}
            """);

        long lateId = createScenarioAndGetId("""
            {"name":"Delay key customer","kind":"LATE_PAYMENT","inputs":[
              {"invoiceId":%d,"delayDays":2}
            ]}
            """.formatted(smeInvoice1));

        long contractId = createScenarioAndGetId("""
            {"name":"Signed retainer","kind":"NEW_CONTRACT","inputs":[
              {"amount":"250.30","expectedDate":"2026-02-11"},
              {"amount":100.5,"expectedDate":"2026-02-15"}
            ]}
            """);

        mockMvc.perform(get("/api/smes/{smeId}/scenarios", sme))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].id").value(baselineId))
            .andExpect(jsonPath("$[0].kind").value("BASELINE"))
            .andExpect(jsonPath("$[0].inputs", hasSize(0)))
            .andExpect(jsonPath("$[1].id").value(lateId))
            .andExpect(jsonPath("$[1].kind").value("LATE_PAYMENT"))
            .andExpect(jsonPath("$[1].inputs[0].invoiceId").value(smeInvoice1))
            .andExpect(jsonPath("$[1].inputs[0].delayDays").value(2))
            .andExpect(jsonPath("$[1].inputs[0].amount").value(nullValue()))
            .andExpect(jsonPath("$[2].id").value(contractId))
            .andExpect(jsonPath("$[2].kind").value("NEW_CONTRACT"))
            .andExpect(jsonPath("$[2].inputs", hasSize(2)))
            .andExpect(jsonPath("$[2].inputs[0].amount").value("250.30"))
            .andExpect(jsonPath("$[2].inputs[1].amount").value("100.50"));

        mockMvc.perform(delete("/api/smes/{smeId}/scenarios/{scenarioId}", sme, lateId))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/smes/{smeId}/scenarios/{scenarioId}", sme, lateId))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/smes/{smeId}/scenarios", sme))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void simulatesBaselineLatePaymentAndNewContractWithoutMutatingFinancialTables() throws Exception {
        List<Map<String, Object>> txSnapshot = jdbc.queryForList(
            "SELECT id, invoice_id, booked_date, direction, amount FROM financial_transactions WHERE sme_id = ? ORDER BY id",
            sme
        );
        List<Map<String, Object>> invoiceSnapshot = jdbc.queryForList(
            "SELECT id, payment_status, amount FROM invoices WHERE sme_id = ? ORDER BY id",
            sme
        );

        long baselineId = createScenarioAndGetId("""
            {"name":"Baseline","kind":"BASELINE"}
            """);
        long lateId = createScenarioAndGetId("""
            {"name":"Late payment mixed","kind":"LATE_PAYMENT","inputs":[
              {"invoiceId":%d,"delayDays":2},
              {"invoiceId":%d,"delayDays":3},
              {"invoiceId":%d,"delayDays":5}
            ]}
            """.formatted(smeInvoice1, smeInvoice2, smeUnpaidInvoice));
        long contractId = createScenarioAndGetId("""
            {"name":"New contract mixed","kind":"NEW_CONTRACT","inputs":[
              {"amount":"60.00","expectedDate":"2026-02-11"},
              {"amount":"400.00","expectedDate":"2026-02-20"}
            ]}
            """);

        String simulateBody = """
            {"openingDate":"2026-02-10","openingAmount":"50.00","currency":"USD","endDate":"2026-02-12"}
            """;

        // 1. BASELINE
        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, baselineId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(simulateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smeId").value(sme))
            .andExpect(jsonPath("$.opening.amount").value("50.00"))
            .andExpect(jsonPath("$.warningThreshold").value("100.00"))
            .andExpect(jsonPath("$.baselineMinClosingBalance.date").value("2026-02-11"))
            .andExpect(jsonPath("$.baselineMinClosingBalance.amount").value("20.50"))
            .andExpect(jsonPath("$.scenarioMinClosingBalance.amount").value("20.50"))
            .andExpect(jsonPath("$.minClosingBalanceDelta").value("0.00"))
            .andExpect(jsonPath("$.days", hasSize(3)))
            .andExpect(jsonPath("$.notes", hasSize(0)))
            .andExpect(jsonPath("$.disclaimer", containsString("not a guarantee")));

        // 2. LATE_PAYMENT:
        // Day 0 (Feb 10): baseline +120.50 -> 170.50; scenario 0.00 -> 50.00 (delta -120.50)
        // Day 1 (Feb 11): baseline -150.00 -> 20.50; scenario -150.00 -> -100.00 (delta -120.50)
        // Day 2 (Feb 12): baseline +80.00 -> 100.50; scenario +120.50 (smeInvoice1 shifted) -> 20.50 (delta -80.00)
        // smeInvoice2 shifted Feb 12 + 3 = Feb 15 (> Feb 12) -> note
        // smeUnpaidInvoice has 0 payments -> note
        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, lateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(simulateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.baselineMinClosingBalance.amount").value("20.50"))
            .andExpect(jsonPath("$.scenarioMinClosingBalance.date").value("2026-02-11"))
            .andExpect(jsonPath("$.scenarioMinClosingBalance.amount").value("-100.00"))
            .andExpect(jsonPath("$.minClosingBalanceDelta").value("-120.50"))
            .andExpect(jsonPath("$.days[0].baselineClosingBalance").value("170.50"))
            .andExpect(jsonPath("$.days[0].scenarioClosingBalance").value("50.00"))
            .andExpect(jsonPath("$.days[0].closingBalanceDelta").value("-120.50"))
            .andExpect(jsonPath("$.days[1].scenarioClosingBalance").value("-100.00"))
            .andExpect(jsonPath("$.days[2].scenarioInflow").value("120.50"))
            .andExpect(jsonPath("$.days[2].scenarioClosingBalance").value("20.50"))
            .andExpect(jsonPath("$.days[2].closingBalanceDelta").value("-80.00"))
            .andExpect(jsonPath("$.baselineWarnings", hasSize(1)))
            .andExpect(jsonPath("$.scenarioWarnings", hasSize(3)))
            .andExpect(jsonPath("$.scenarioWarnings[1].kind").value("POTENTIAL_CASH_SHORTFALL"))
            .andExpect(jsonPath("$.notes", hasSize(2)))
            .andExpect(jsonPath("$.notes[0]", containsString("80.00")))
            .andExpect(jsonPath("$.notes[1]", containsString("no recorded payments")));

        // 3. NEW_CONTRACT:
        // Day 1 (Feb 11): +60.00 inflow added -> scenario closing 80.50 vs baseline 20.50
        // Feb 20 contract (400.00) is outside [Feb 10, Feb 12] -> note
        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, contractId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(simulateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.baselineMinClosingBalance.amount").value("20.50"))
            .andExpect(jsonPath("$.scenarioMinClosingBalance.amount").value("80.50"))
            .andExpect(jsonPath("$.minClosingBalanceDelta").value("60.00"))
            .andExpect(jsonPath("$.days[1].scenarioInflow").value("60.00"))
            .andExpect(jsonPath("$.days[1].scenarioClosingBalance").value("80.50"))
            .andExpect(jsonPath("$.days[1].closingBalanceDelta").value("60.00"))
            .andExpect(jsonPath("$.notes", hasSize(1)))
            .andExpect(jsonPath("$.notes[0]", containsString("400.00")));

        // Verify DB tables are untouched after all simulations
        assertEquals(txSnapshot, jdbc.queryForList(
            "SELECT id, invoice_id, booked_date, direction, amount FROM financial_transactions WHERE sme_id = ? ORDER BY id",
            sme
        ));
        assertEquals(invoiceSnapshot, jdbc.queryForList(
            "SELECT id, payment_status, amount FROM invoices WHERE sme_id = ? ORDER BY id",
            sme
        ));
    }

    @Test
    void rejectsInvalidCreatePayloadsAndCrossSmeOwnershipWithoutLeakingSql() throws Exception {
        mockMvc.perform(post("/api/smes/{smeId}/scenarios", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Bad SME","kind":"BASELINE","inputs":[]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/api/smes/{smeId}/scenarios", 999999L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Unknown SME","kind":"BASELINE","inputs":[]}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));

        // Blank name & unknown kind
        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"   ","kind":"BASELINE"}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Invalid kind","kind":"MAGIC"}
                    """))
            .andExpect(status().isBadRequest());

        // BASELINE with inputs
        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Bad baseline","kind":"BASELINE","inputs":[{"amount":"10.00","expectedDate":"2026-02-11"}]}
                    """))
            .andExpect(status().isBadRequest());

        // LATE_PAYMENT validations
        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Empty late","kind":"LATE_PAYMENT","inputs":[]}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Delay zero","kind":"LATE_PAYMENT","inputs":[{"invoiceId":%d,"delayDays":0}]}
                    """.formatted(smeInvoice1)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Late with amount","kind":"LATE_PAYMENT","inputs":[{"invoiceId":%d,"delayDays":5,"amount":"10.00"}]}
                    """.formatted(smeInvoice1)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Duplicate invoice","kind":"LATE_PAYMENT","inputs":[
                      {"invoiceId":%d,"delayDays":2},
                      {"invoiceId":%d,"delayDays":5}
                    ]}
                    """.formatted(smeInvoice1, smeInvoice1)))
            .andExpect(status().isBadRequest());

        // Cross-SME invoice ownership check -> clean 404 without SQL details
        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Cross SME invoice","kind":"LATE_PAYMENT","inputs":[{"invoiceId":%d,"delayDays":3}]}
                    """.formatted(otherInvoice)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message", not(containsString("SELECT"))))
            .andExpect(jsonPath("$.message", not(containsString("FOREIGN KEY"))));

        // NEW_CONTRACT validations: fractional minor units, non-positive, invalid fields
        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Fractional","kind":"NEW_CONTRACT","inputs":[{"amount":"10.005","expectedDate":"2026-02-11"}]}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Non-positive","kind":"NEW_CONTRACT","inputs":[{"amount":"0.00","expectedDate":"2026-02-11"}]}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"With invoiceId","kind":"NEW_CONTRACT","inputs":[{"invoiceId":%d,"amount":"10.00","expectedDate":"2026-02-11"}]}
                    """.formatted(smeInvoice1)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidListDeleteAndSimulateRequests() throws Exception {
        long otherScenarioId = jdbc.queryForObject("""
            INSERT INTO scenarios(sme_id, name, kind) VALUES (?, 'Other baseline', 'BASELINE') RETURNING id
            """, Long.class, otherSme);
        long smeScenarioId = createScenarioAndGetId("""
            {"name":"Mine","kind":"BASELINE","inputs":[]}
            """);

        mockMvc.perform(get("/api/smes/{smeId}/scenarios", 999999L))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/smes/{smeId}/scenarios/{scenarioId}", sme, otherScenarioId))
            .andExpect(status().isNotFound());

        // Unknown SME / cross-SME scenario simulate -> 404
        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", 999999L, smeScenarioId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"openingDate":"2026-02-10","openingAmount":"50.00","currency":"USD","endDate":"2026-02-12"}
                    """))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, otherScenarioId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"openingDate":"2026-02-10","openingAmount":"50.00","currency":"USD","endDate":"2026-02-12"}
                    """))
            .andExpect(status().isNotFound());

        // Currency mismatch -> 400
        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, smeScenarioId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"openingDate":"2026-02-10","openingAmount":"50.00","currency":"EUR","endDate":"2026-02-12"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.path").value("/api/smes/" + sme + "/scenarios/" + smeScenarioId + "/simulate"));

        // Fractional / malformed openingAmount -> 400
        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, smeScenarioId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"openingDate":"2026-02-10","openingAmount":"50.001","currency":"USD","endDate":"2026-02-12"}
                    """))
            .andExpect(status().isBadRequest());

        // Reversed or oversized date range -> 400
        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, smeScenarioId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"openingDate":"2026-02-12","openingAmount":"50.00","currency":"USD","endDate":"2026-02-10"}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/smes/{smeId}/scenarios/{scenarioId}/simulate", sme, smeScenarioId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"openingDate":"2026-02-01","openingAmount":"50.00","currency":"USD","endDate":"2026-03-04"}
                    """))
            .andExpect(status().isBadRequest());
    }

    private long createScenarioAndGetId(String jsonBody) throws Exception {
        String body = mockMvc.perform(post("/api/smes/{smeId}/scenarios", sme)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("id").asLong();
    }
}
