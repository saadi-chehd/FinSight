package com.finsight.forecast;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/** DTOs for the Python /forecast wire protocol and the Java /api/smes/{smeId}/forecast response. */
public final class ForecastModels {
    private ForecastModels() { }

    public record PythonHistoryEntry(
            LocalDate date,
            @JsonProperty("closing_balance") String closingBalance) { }

    public record PythonForecastRequest(
            String currency,
            List<PythonHistoryEntry> history,
            List<Integer> horizons,
            String model) {
        public PythonForecastRequest {
            history = List.copyOf(history);
            horizons = List.copyOf(horizons);
        }
    }

    public record PythonIntervalBounds(String lower, String upper) { }

    public record PythonModelMetadata(
            String name,
            String version,
            String description,
            @JsonProperty("interval_method") String intervalMethod,
            @JsonProperty("min_history_days") int minHistoryDays,
            @JsonProperty("residual_window_max") int residualWindowMax) { }

    public record PythonHorizonForecast(
            @JsonProperty("horizon_days") int horizonDays,
            @JsonProperty("target_date") LocalDate targetDate,
            @JsonProperty("predicted_closing_balance") String predictedClosingBalance,
            @JsonProperty("interval_80") PythonIntervalBounds interval80,
            @JsonProperty("interval_95") PythonIntervalBounds interval95,
            @JsonProperty("residual_count") int residualCount) { }

    public record PythonForecastResponse(
            String currency,
            @JsonProperty("origin_date") LocalDate originDate,
            @JsonProperty("history_start_date") LocalDate historyStartDate,
            @JsonProperty("history_days") int historyDays,
            PythonModelMetadata model,
            List<PythonHorizonForecast> forecasts,
            String disclaimer) {
        public PythonForecastResponse {
            forecasts = forecasts == null ? List.of() : List.copyOf(forecasts);
        }
    }

    public record IntervalBounds(String lower, String upper) { }

    public record ModelMetadata(
            String name,
            String version,
            String description,
            String intervalMethod,
            int minHistoryDays,
            int residualWindowMax) { }

    public record HorizonForecast(
            int horizonDays,
            LocalDate targetDate,
            String predictedClosingBalance,
            IntervalBounds interval80,
            IntervalBounds interval95,
            int residualCount) { }

    public record SmeForecastResponse(
            long smeId,
            LocalDate openingDate,
            String openingAmount,
            LocalDate endDate,
            String currency,
            LocalDate originDate,
            LocalDate historyStartDate,
            int historyDays,
            ModelMetadata model,
            List<HorizonForecast> forecasts,
            String disclaimer) {
        public SmeForecastResponse {
            forecasts = List.copyOf(forecasts);
        }
    }
}
