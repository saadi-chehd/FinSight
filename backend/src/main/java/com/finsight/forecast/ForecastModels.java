package com.finsight.forecast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DTOs for the Python /forecast wire protocol and the Java /api/smes/{smeId}/forecast response. */
public final class ForecastModels {
    private ForecastModels() { }

    public record PythonHistoryEntry(
            LocalDate date,
            @JsonProperty("closing_balance") String closingBalance) { }

    public record PythonOpenInvoice(
            String direction,
            @JsonProperty("due_date") LocalDate dueDate,
            String outstanding) { }

    public record PythonForecastRequest(
            String currency,
            List<PythonHistoryEntry> history,
            List<Integer> horizons,
            String model,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("open_invoices") List<PythonOpenInvoice> openInvoices) {
        public PythonForecastRequest {
            history = List.copyOf(history);
            horizons = List.copyOf(horizons);
            openInvoices = openInvoices == null ? null : List.copyOf(openInvoices);
        }

        public PythonForecastRequest(
                String currency,
                List<PythonHistoryEntry> history,
                List<Integer> horizons,
                String model) {
            this(currency, history, horizons, model, null);
        }
    }

    public record PythonIntervalBounds(String lower, String upper) { }

    public record PythonModelMetadata(
            String name,
            String version,
            String description,
            @JsonProperty("interval_method") String intervalMethod,
            @JsonProperty("min_history_days") int minHistoryDays,
            @JsonProperty("residual_window_max") int residualWindowMax,
            @JsonProperty("uses_open_invoices") boolean usesOpenInvoices,
            @JsonProperty("open_invoice_count") Integer openInvoiceCount,
            @JsonProperty("active_members") List<String> activeMembers,
            @JsonProperty("ensemble_weights") Map<String, Map<String, Double>> ensembleWeights) {
        public PythonModelMetadata {
            activeMembers = activeMembers == null ? null : List.copyOf(activeMembers);
            ensembleWeights = copyEnsembleWeights(ensembleWeights);
        }

        @JsonCreator
        public static PythonModelMetadata fromJson(
                @JsonProperty("name") String name,
                @JsonProperty("version") String version,
                @JsonProperty("description") String description,
                @JsonProperty("interval_method") String intervalMethod,
                @JsonProperty("min_history_days") int minHistoryDays,
                @JsonProperty("residual_window_max") int residualWindowMax,
                @JsonProperty("uses_open_invoices") Boolean usesOpenInvoices,
                @JsonProperty("open_invoice_count") Integer openInvoiceCount,
                @JsonProperty("active_members") List<String> activeMembers,
                @JsonProperty("ensemble_weights") Map<String, Map<String, Double>> ensembleWeights) {
            return new PythonModelMetadata(
                    name,
                    version,
                    description,
                    intervalMethod,
                    minHistoryDays,
                    residualWindowMax,
                    Boolean.TRUE.equals(usesOpenInvoices),
                    openInvoiceCount,
                    activeMembers,
                    ensembleWeights);
        }

        public PythonModelMetadata(
                String name,
                String version,
                String description,
                String intervalMethod,
                int minHistoryDays,
                int residualWindowMax) {
            this(name, version, description, intervalMethod, minHistoryDays, residualWindowMax, false, null, null, null);
        }
    }

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
            int residualWindowMax,
            boolean usesOpenInvoices,
            Integer openInvoiceCount,
            List<String> activeMembers,
            Map<String, Map<String, Double>> ensembleWeights) {
        public ModelMetadata {
            activeMembers = activeMembers == null ? null : List.copyOf(activeMembers);
            ensembleWeights = copyEnsembleWeights(ensembleWeights);
        }

        public ModelMetadata(
                String name,
                String version,
                String description,
                String intervalMethod,
                int minHistoryDays,
                int residualWindowMax) {
            this(name, version, description, intervalMethod, minHistoryDays, residualWindowMax, false, null, null, null);
        }
    }

    private static Map<String, Map<String, Double>> copyEnsembleWeights(
            Map<String, Map<String, Double>> source) {
        if (source == null) {
            return null;
        }
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : source.entrySet()) {
            Map<String, Double> inner = entry.getValue();
            copy.put(entry.getKey(), inner == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(inner)));
        }
        return Collections.unmodifiableMap(copy);
    }

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
