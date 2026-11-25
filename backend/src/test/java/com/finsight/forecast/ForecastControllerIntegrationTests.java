package com.finsight.forecast;

import com.finsight.FinancialRecordRepository;
import com.finsight.PostgresTestSupport;
import com.finsight.api.DownstreamServiceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static com.finsight.forecast.ForecastModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ForecastControllerIntegrationTests extends PostgresTestSupport {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FinancialRecordRepository records;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ForecastClient forecastClient;

    private long smeId;

    @BeforeEach
    void setUp() {
        smeId = records.createSme("Forecast Controller Fixture", "USD");
        records.recordTransaction(smeId, "USD", LocalDate.of(2024, 1, 1), "INFLOW", "CUSTOMER_RECEIPT", new BigDecimal("120.50"));
        records.recordTransaction(smeId, "USD", LocalDate.of(2024, 1, 2), "OUTFLOW", "SUPPLIER_PAYMENT", new BigDecimal("20.00"));
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("scenario_inputs", "financial_transactions", "scenarios", "invoices")) {
            jdbc.update("DELETE FROM " + table + " WHERE sme_id = ?", smeId);
        }
        jdbc.update("DELETE FROM smes WHERE id = ?", smeId);
    }

    @Test
    void returnsEnrichedForecastWithDefaultHorizonsAndModel() throws Exception {
        PythonForecastResponse downstream = new PythonForecastResponse(
                "USD",
                LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 1),
                3,
                new PythonModelMetadata(
                        "drift_calendar",
                        "CM1",
                        "Drift and calendar model",
                        "split_conformal_past_rolling_residuals",
                        109,
                        365),
                List.of(
                        new PythonHorizonForecast(
                                7,
                                LocalDate.of(2024, 1, 10),
                                "5200.50",
                                new PythonIntervalBounds("4900.00", "5501.00"),
                                new PythonIntervalBounds("4600.00", "5801.00"),
                                250),
                        new PythonHorizonForecast(
                                30,
                                LocalDate.of(2024, 2, 2),
                                "5600.00",
                                new PythonIntervalBounds("4800.00", "6400.00"),
                                new PythonIntervalBounds("4200.00", "7000.00"),
                                220)),
                "Conditional mathematical estimates derived solely from historical daily closing cash balances.");

        when(forecastClient.forecast(any())).thenReturn(downstream);

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smeId").value(smeId))
                .andExpect(jsonPath("$.openingDate").value("2024-01-01"))
                .andExpect(jsonPath("$.openingAmount").value("5000.00"))
                .andExpect(jsonPath("$.endDate").value("2024-01-03"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.originDate").value("2024-01-03"))
                .andExpect(jsonPath("$.historyStartDate").value("2024-01-01"))
                .andExpect(jsonPath("$.historyDays").value(3))
                .andExpect(jsonPath("$.model.name").value("drift_calendar"))
                .andExpect(jsonPath("$.model.version").value("CM1"))
                .andExpect(jsonPath("$.model.intervalMethod").value("split_conformal_past_rolling_residuals"))
                .andExpect(jsonPath("$.model.minHistoryDays").value(109))
                .andExpect(jsonPath("$.model.residualWindowMax").value(365))
                .andExpect(jsonPath("$.forecasts.length()").value(2))
                .andExpect(jsonPath("$.forecasts[0].horizonDays").value(7))
                .andExpect(jsonPath("$.forecasts[0].targetDate").value("2024-01-10"))
                .andExpect(jsonPath("$.forecasts[0].predictedClosingBalance").value("5200.50"))
                .andExpect(jsonPath("$.forecasts[0].interval80.lower").value("4900.00"))
                .andExpect(jsonPath("$.forecasts[0].interval80.upper").value("5501.00"))
                .andExpect(jsonPath("$.forecasts[0].interval95.lower").value("4600.00"))
                .andExpect(jsonPath("$.forecasts[0].interval95.upper").value("5801.00"))
                .andExpect(jsonPath("$.forecasts[0].residualCount").value(250))
                .andExpect(jsonPath("$.disclaimer").value("Conditional mathematical estimates derived solely from historical daily closing cash balances."));

        ArgumentCaptor<PythonForecastRequest> captor = ArgumentCaptor.forClass(PythonForecastRequest.class);
        verify(forecastClient).forecast(captor.capture());
        PythonForecastRequest sent = captor.getValue();
        assertEquals("USD", sent.currency());
        assertEquals(List.of(7, 30), sent.horizons());
        assertEquals("drift_calendar", sent.model());
        assertEquals(List.of(
                new PythonHistoryEntry(LocalDate.of(2024, 1, 1), "5120.50"),
                new PythonHistoryEntry(LocalDate.of(2024, 1, 2), "5100.50"),
                new PythonHistoryEntry(LocalDate.of(2024, 1, 3), "5100.50")),
                sent.history());
    }

    @Test
    void supportsCustomHorizonsAndModel() throws Exception {
        PythonForecastResponse downstream = new PythonForecastResponse(
                "USD",
                LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 1),
                3,
                new PythonModelMetadata(
                        "ridge_lag_calendar",
                        "CM1",
                        "Ridge model",
                        "split_conformal_past_rolling_residuals",
                        109,
                        365),
                List.of(
                        new PythonHorizonForecast(
                                14,
                                LocalDate.of(2024, 1, 17),
                                "5300.00",
                                new PythonIntervalBounds("5000.00", "5600.00"),
                                new PythonIntervalBounds("4800.00", "5800.00"),
                                200)),
                "Conditional mathematical estimates.");

        when(forecastClient.forecast(any())).thenReturn(downstream);

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03")
                        .param("horizons", "14,1")
                        .param("model", "ridge_lag_calendar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.name").value("ridge_lag_calendar"))
                .andExpect(jsonPath("$.forecasts[0].horizonDays").value(14));

        ArgumentCaptor<PythonForecastRequest> captor = ArgumentCaptor.forClass(PythonForecastRequest.class);
        verify(forecastClient).forecast(captor.capture());
        assertEquals(List.of(14, 1), captor.getValue().horizons());
        assertEquals("ridge_lag_calendar", captor.getValue().model());
    }

    @Test
    void rejectsInvalidParametersBeforeCallingForecastClient() throws Exception {
        // Missing required param
        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/smes/" + smeId + "/forecast"));

        // Fractional minor units
        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "100.123")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Unsupported currency
        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "JPY")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Currency mismatch against SME
        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "EUR")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Reversed dates
        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-05")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Unsupported model
        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03")
                        .param("model", "arima"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Invalid horizons (duplicates, out-of-range, blank, non-numeric)
        for (String badHorizons : List.of("7,7", "0", "366", "", "7,abc")) {
            mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                            .param("openingDate", "2024-01-01")
                            .param("openingAmount", "5000.00")
                            .param("currency", "USD")
                            .param("endDate", "2024-01-03")
                            .param("horizons", badHorizons))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        verifyNoInteractions(forecastClient);
    }

    @Test
    void returns404ForUnknownSme() throws Exception {
        mockMvc.perform(get("/api/smes/{smeId}/forecast", 999999L)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/smes/999999/forecast"));

        verifyNoInteractions(forecastClient);
    }

    @Test
    void propagatesDownstream422ValidationError() throws Exception {
        when(forecastClient.forecast(any())).thenThrow(new DownstreamServiceException(
                422,
                "FORECAST_VALIDATION_ERROR",
                "Insufficient history for model 'drift_calendar' at max horizon 30: requires at least 109 consecutive daily observations, received 3"));

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("FORECAST_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Insufficient history for model 'drift_calendar' at max horizon 30: requires at least 109 consecutive daily observations, received 3"))
                .andExpect(jsonPath("$.path").value("/api/smes/" + smeId + "/forecast"));
    }

    @Test
    void propagatesDownstream503UnavailableError() throws Exception {
        when(forecastClient.forecast(any())).thenThrow(new DownstreamServiceException(
                503,
                "FORECAST_SERVICE_UNAVAILABLE",
                "Forecasting service is unavailable or timed out"));

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("FORECAST_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Forecasting service is unavailable or timed out"))
                .andExpect(jsonPath("$.path").value("/api/smes/" + smeId + "/forecast"));
    }

    @Test
    void sendsPointInTimeOpenInvoicesForInvoiceAwareAndEnsembleModelsAndMapsCm2Metadata() throws Exception {
        // 1) Unpaid open inflow invoice issued <= endDate (2024-01-03) -> included with 1000.00
        insertInvoice("INV-OPEN-IN", "INFLOW", "1000.00", "UNPAID",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 15));

        // 2) Partially paid outflow invoice as of 2024-01-03, with a future payment on 2024-01-04
        //    Future payment MUST be ignored as of endDate=2024-01-03 -> outstanding = 350.00
        long partialId = insertInvoice("INV-PARTIAL-OUT", "OUTFLOW", "500.00", "PAID",
                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 20));
        insertLinkedTransaction(partialId, LocalDate.of(2024, 1, 3), "OUTFLOW", "SUPPLIER_PAYMENT", "150.00");
        insertLinkedTransaction(partialId, LocalDate.of(2024, 1, 4), "OUTFLOW", "SUPPLIER_PAYMENT", "350.00");

        // 3) Cancelled invoice -> excluded
        insertInvoice("INV-CANCELLED", "INFLOW", "750.00", "CANCELLED",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10));

        // 4) Fully paid invoice as of 2024-01-03 -> excluded
        long paidId = insertInvoice("INV-PAID", "INFLOW", "200.00", "PAID",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 12));
        insertLinkedTransaction(paidId, LocalDate.of(2024, 1, 2), "INFLOW", "CUSTOMER_RECEIPT", "200.00");

        // 5) Future invoice issued after endDate (2024-01-04 > 2024-01-03) -> excluded
        insertInvoice("INV-FUTURE", "INFLOW", "999.00", "UNPAID",
                LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 25));

        List<PythonOpenInvoice> expectedOpenInvoices = List.of(
                new PythonOpenInvoice("INFLOW", LocalDate.of(2024, 1, 15), "1000.00"),
                new PythonOpenInvoice("OUTFLOW", LocalDate.of(2024, 1, 20), "350.00"));

        PythonForecastResponse invoiceAwareDownstream = new PythonForecastResponse(
                "USD",
                LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 1),
                3,
                new PythonModelMetadata(
                        "invoice_aware_ml",
                        "CM2",
                        "Invoice-aware ML model",
                        "split_conformal_past_rolling_residuals",
                        139,
                        180,
                        true,
                        2,
                        null,
                        null),
                List.of(
                        new PythonHorizonForecast(
                                7,
                                LocalDate.of(2024, 1, 10),
                                "5550.00",
                                new PythonIntervalBounds("5250.00", "5850.00"),
                                new PythonIntervalBounds("5000.00", "6100.00"),
                                90)),
                "Conditional mathematical estimates.");

        when(forecastClient.forecast(any())).thenReturn(invoiceAwareDownstream);

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03")
                        .param("horizons", "7")
                        .param("model", "invoice_aware_ml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.name").value("invoice_aware_ml"))
                .andExpect(jsonPath("$.model.version").value("CM2"))
                .andExpect(jsonPath("$.model.usesOpenInvoices").value(true))
                .andExpect(jsonPath("$.model.openInvoiceCount").value(2))
                .andExpect(jsonPath("$.model.activeMembers").doesNotExist())
                .andExpect(jsonPath("$.model.ensembleWeights").doesNotExist());

        PythonForecastResponse ensembleDownstream = new PythonForecastResponse(
                "USD",
                LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 1),
                3,
                new PythonModelMetadata(
                        "ensemble_ml",
                        "CM2",
                        "Weighted ensemble ML model",
                        "split_conformal_past_rolling_residuals",
                        139,
                        180,
                        true,
                        2,
                        List.of("drift_calendar", "gbdt_direct", "invoice_aware_ml"),
                        Map.of("7", Map.of("drift_calendar", 0.25, "gbdt_direct", 0.35, "invoice_aware_ml", 0.40))),
                List.of(
                        new PythonHorizonForecast(
                                7,
                                LocalDate.of(2024, 1, 10),
                                "5450.00",
                                new PythonIntervalBounds("5150.00", "5750.00"),
                                new PythonIntervalBounds("4900.00", "6000.00"),
                                90)),
                "Conditional mathematical estimates.");

        when(forecastClient.forecast(any())).thenReturn(ensembleDownstream);

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03")
                        .param("horizons", "7")
                        .param("model", "ensemble_ml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.name").value("ensemble_ml"))
                .andExpect(jsonPath("$.model.version").value("CM2"))
                .andExpect(jsonPath("$.model.usesOpenInvoices").value(true))
                .andExpect(jsonPath("$.model.openInvoiceCount").value(2))
                .andExpect(jsonPath("$.model.activeMembers[0]").value("drift_calendar"))
                .andExpect(jsonPath("$.model.activeMembers[1]").value("gbdt_direct"))
                .andExpect(jsonPath("$.model.activeMembers[2]").value("invoice_aware_ml"))
                .andExpect(jsonPath("$.model.ensembleWeights['7'].drift_calendar").value(0.25))
                .andExpect(jsonPath("$.model.ensembleWeights['7'].gbdt_direct").value(0.35))
                .andExpect(jsonPath("$.model.ensembleWeights['7'].invoice_aware_ml").value(0.40));

        ArgumentCaptor<PythonForecastRequest> captor = ArgumentCaptor.forClass(PythonForecastRequest.class);
        verify(forecastClient, times(2)).forecast(captor.capture());
        List<PythonForecastRequest> requests = captor.getAllValues();
        assertEquals("invoice_aware_ml", requests.get(0).model());
        assertEquals(expectedOpenInvoices, requests.get(0).openInvoices());
        assertEquals("ensemble_ml", requests.get(1).model());
        assertEquals(expectedOpenInvoices, requests.get(1).openInvoices());
    }

    @Test
    void sendsEmptyOpenInvoicesListWhenNoneAreOpenAndNullForGbdtDirect() throws Exception {
        PythonForecastResponse invoiceAwareDownstream = new PythonForecastResponse(
                "USD",
                LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 1),
                3,
                new PythonModelMetadata(
                        "invoice_aware_ml",
                        "CM2",
                        "Invoice-aware ML model",
                        "split_conformal_past_rolling_residuals",
                        139,
                        180,
                        true,
                        0,
                        null,
                        null),
                List.of(
                        new PythonHorizonForecast(
                                7,
                                LocalDate.of(2024, 1, 10),
                                "5100.50",
                                new PythonIntervalBounds("4900.00", "5300.00"),
                                new PythonIntervalBounds("4700.00", "5500.00"),
                                90)),
                "Conditional mathematical estimates.");

        when(forecastClient.forecast(any())).thenReturn(invoiceAwareDownstream);

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03")
                        .param("horizons", "7")
                        .param("model", "invoice_aware_ml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.usesOpenInvoices").value(true))
                .andExpect(jsonPath("$.model.openInvoiceCount").value(0));

        PythonForecastResponse gbdtDownstream = new PythonForecastResponse(
                "USD",
                LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 1),
                3,
                new PythonModelMetadata(
                        "gbdt_direct",
                        "CM2",
                        "GBDT direct model",
                        "split_conformal_past_rolling_residuals",
                        139,
                        180),
                List.of(
                        new PythonHorizonForecast(
                                7,
                                LocalDate.of(2024, 1, 10),
                                "5120.00",
                                new PythonIntervalBounds("4920.00", "5320.00"),
                                new PythonIntervalBounds("4720.00", "5520.00"),
                                90)),
                "Conditional mathematical estimates.");

        when(forecastClient.forecast(any())).thenReturn(gbdtDownstream);

        mockMvc.perform(get("/api/smes/{smeId}/forecast", smeId)
                        .param("openingDate", "2024-01-01")
                        .param("openingAmount", "5000.00")
                        .param("currency", "USD")
                        .param("endDate", "2024-01-03")
                        .param("horizons", "7")
                        .param("model", "gbdt_direct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.name").value("gbdt_direct"))
                .andExpect(jsonPath("$.model.version").value("CM2"))
                .andExpect(jsonPath("$.model.usesOpenInvoices").value(false));

        ArgumentCaptor<PythonForecastRequest> captor = ArgumentCaptor.forClass(PythonForecastRequest.class);
        verify(forecastClient, times(2)).forecast(captor.capture());
        List<PythonForecastRequest> requests = captor.getAllValues();
        assertEquals(List.of(), requests.get(0).openInvoices());
        assertNotNull(requests.get(0).openInvoices());
        assertEquals("gbdt_direct", requests.get(1).model());
        assertNull(requests.get(1).openInvoices());
    }

    private long insertInvoice(
            String reference,
            String direction,
            String amount,
            String paymentStatus,
            LocalDate issueDate,
            LocalDate dueDate) {
        return jdbc.queryForObject("""
                INSERT INTO invoices(sme_id, currency, reference, direction, amount, payment_status, issue_date, due_date)
                VALUES (?, 'USD', ?, ?, ?::numeric, ?, ?, ?) RETURNING id
                """, Long.class, smeId, reference, direction, amount, paymentStatus, issueDate, dueDate);
    }

    private void insertLinkedTransaction(
            long invoiceId,
            LocalDate bookedDate,
            String direction,
            String type,
            String amount) {
        jdbc.update("""
                INSERT INTO financial_transactions(sme_id, currency, invoice_id, booked_date, direction, transaction_type, amount)
                VALUES (?, 'USD', ?, ?, ?, ?, ?::numeric)
                """, smeId, invoiceId, bookedDate, direction, type, amount);
    }
}
