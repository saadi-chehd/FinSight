package com.finsight.forecast;

import com.finsight.api.DownstreamServiceException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static com.finsight.forecast.ForecastModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ForecastClientTests {
    private MockRestServiceServer server;
    private ForecastClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ForecastClient(builder, JsonMapper.builder().build());
    }

    @Test
    void sendsExactPythonContractJsonAndParsesSuccessResponse() {
        PythonForecastRequest request = new PythonForecastRequest(
                "USD",
                List.of(
                        new PythonHistoryEntry(LocalDate.of(2024, 1, 1), "5000.00"),
                        new PythonHistoryEntry(LocalDate.of(2024, 1, 2), "5125.50")),
                List.of(7, 30),
                "drift_calendar");

        String expectedRequestJson = """
                {
                  "currency": "USD",
                  "history": [
                    {"date": "2024-01-01", "closing_balance": "5000.00"},
                    {"date": "2024-01-02", "closing_balance": "5125.50"}
                  ],
                  "horizons": [7, 30],
                  "model": "drift_calendar"
                }
                """;

        String pythonResponseJson = """
                {
                  "currency": "USD",
                  "origin_date": "2024-01-02",
                  "history_start_date": "2024-01-01",
                  "history_days": 2,
                  "model": {
                    "name": "drift_calendar",
                    "version": "CM1",
                    "description": "Drift and calendar model",
                    "interval_method": "split_conformal_past_rolling_residuals",
                    "min_history_days": 109,
                    "residual_window_max": 365
                  },
                  "forecasts": [
                    {
                      "horizon_days": 7,
                      "target_date": "2024-01-09",
                      "predicted_closing_balance": "5400.25",
                      "interval_80": {"lower": "4900.10", "upper": "5900.40"},
                      "interval_95": {"lower": "4500.00", "upper": "6300.50"},
                      "residual_count": 300
                    },
                    {
                      "horizon_days": 30,
                      "target_date": "2024-02-01",
                      "predicted_closing_balance": "6100.00",
                      "interval_80": {"lower": "5200.00", "upper": "7000.00"},
                      "interval_95": {"lower": "4700.00", "upper": "7500.00"},
                      "residual_count": 280
                    }
                  ],
                  "disclaimer": "Conditional mathematical estimates derived solely from historical daily closing cash balances."
                }
                """;

        server.expect(requestTo("http://127.0.0.1:8000/forecast"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedRequestJson, true))
                .andRespond(withSuccess(pythonResponseJson, MediaType.APPLICATION_JSON));

        PythonForecastResponse response = client.forecast(request);
        server.verify();

        assertEquals("USD", response.currency());
        assertEquals(LocalDate.of(2024, 1, 2), response.originDate());
        assertEquals(LocalDate.of(2024, 1, 1), response.historyStartDate());
        assertEquals(2, response.historyDays());
        assertEquals("drift_calendar", response.model().name());
        assertEquals("CM1", response.model().version());
        assertEquals("split_conformal_past_rolling_residuals", response.model().intervalMethod());
        assertEquals(109, response.model().minHistoryDays());
        assertEquals(365, response.model().residualWindowMax());
        assertEquals(2, response.forecasts().size());

        PythonHorizonForecast h7 = response.forecasts().get(0);
        assertEquals(7, h7.horizonDays());
        assertEquals(LocalDate.of(2024, 1, 9), h7.targetDate());
        assertEquals("5400.25", h7.predictedClosingBalance());
        assertEquals("4900.10", h7.interval80().lower());
        assertEquals("5900.40", h7.interval80().upper());
        assertEquals("4500.00", h7.interval95().lower());
        assertEquals("6300.50", h7.interval95().upper());
        assertEquals(300, h7.residualCount());
    }

    @Test
    void maps422And400ValidationErrorsToDownstream422WithoutLeakingTraces() {
        PythonForecastRequest request = sampleRequest();

        server.expect(requestTo("http://127.0.0.1:8000/forecast"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"detail":"Insufficient history for model 'drift_calendar' at max horizon 30: requires at least 109 consecutive daily observations, received 10"}
                              """));

        DownstreamServiceException ex422 = assertThrows(DownstreamServiceException.class, () -> client.forecast(request));
        server.verify();
        assertEquals(422, ex422.status());
        assertEquals("FORECAST_VALIDATION_ERROR", ex422.error());
        assertTrue(ex422.getMessage().contains("Insufficient history"));

        server.reset();
        server.expect(requestTo("http://127.0.0.1:8000/forecast"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"detail":[{"loc":["body","horizons"],"msg":"Each horizon must be an integer between 1 and 365"}]}
                              """));

        DownstreamServiceException ex400 = assertThrows(DownstreamServiceException.class, () -> client.forecast(request));
        server.verify();
        assertEquals(422, ex400.status());
        assertEquals("FORECAST_VALIDATION_ERROR", ex400.error());
        assertEquals("Each horizon must be an integer between 1 and 365", ex400.getMessage());
    }

    @Test
    void maps5xxAndIoFailuresTo503Unavailable() {
        PythonForecastRequest request = sampleRequest();

        server.expect(requestTo("http://127.0.0.1:8000/forecast"))
                .andRespond(withServerError().body("Traceback (most recent call last): secret stack"));

        DownstreamServiceException ex500 = assertThrows(DownstreamServiceException.class, () -> client.forecast(request));
        server.verify();
        assertEquals(503, ex500.status());
        assertEquals("FORECAST_SERVICE_UNAVAILABLE", ex500.error());
        assertEquals(ForecastClient.SERVICE_UNAVAILABLE_MESSAGE, ex500.getMessage());
        assertFalse(ex500.getMessage().contains("Traceback"));

        server.reset();
        server.expect(requestTo("http://127.0.0.1:8000/forecast"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        DownstreamServiceException exIo = assertThrows(DownstreamServiceException.class, () -> client.forecast(request));
        server.verify();
        assertEquals(503, exIo.status());
        assertEquals("FORECAST_SERVICE_UNAVAILABLE", exIo.error());
        assertEquals(ForecastClient.SERVICE_UNAVAILABLE_MESSAGE, exIo.getMessage());

        server.reset();
        server.expect(requestTo("http://127.0.0.1:8000/forecast"))
                .andRespond(withException(new IOException("Connection refused")));

        DownstreamServiceException exConn = assertThrows(DownstreamServiceException.class, () -> client.forecast(request));
        server.verify();
        assertEquals(503, exConn.status());
        assertEquals("FORECAST_SERVICE_UNAVAILABLE", exConn.error());
        assertEquals(ForecastClient.SERVICE_UNAVAILABLE_MESSAGE, exConn.getMessage());
    }

    private static PythonForecastRequest sampleRequest() {
        return new PythonForecastRequest(
                "USD",
                List.of(new PythonHistoryEntry(LocalDate.of(2024, 1, 1), "5000.00")),
                List.of(7, 30),
                "drift_calendar");
    }
}
