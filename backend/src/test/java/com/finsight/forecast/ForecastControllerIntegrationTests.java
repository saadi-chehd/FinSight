package com.finsight.forecast;

import com.finsight.FinancialRecordRepository;
import com.finsight.PostgresTestSupport;
import com.finsight.api.DownstreamServiceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
}
