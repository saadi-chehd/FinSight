package com.finsight.forecast;

import com.finsight.api.DownstreamServiceException;
import com.finsight.api.MoneyCodec;
import com.finsight.financial.FinancialModels.CashFlowReport;
import com.finsight.financial.FinancialModels.InvoiceSummary;
import com.finsight.financial.FinancialModels.OpeningCash;
import com.finsight.financial.FinancialModels.PaymentStatus;
import com.finsight.financial.FinancialReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import static com.finsight.forecast.ForecastModels.*;

/** Orchestrates historical daily cash-flow computation and live Python forecasting. */
@Service
public class ForecastService {
    private static final String DEFAULT_MODEL = "drift_calendar";
    private static final List<Integer> DEFAULT_HORIZONS = List.of(7, 30);
    private static final Set<String> ALLOWED_MODELS = Set.of(
            "drift_calendar",
            "ridge_lag_calendar",
            "naive",
            "gbdt_direct",
            "invoice_aware_ml",
            "ensemble_ml");
    private static final Set<String> INVOICE_AWARE_MODELS = Set.of("invoice_aware_ml", "ensemble_ml");

    private final FinancialReportService financialReportService;
    private final ForecastClient forecastClient;

    public ForecastService(FinancialReportService financialReportService, ForecastClient forecastClient) {
        this.financialReportService = financialReportService;
        this.forecastClient = forecastClient;
    }

    public SmeForecastResponse forecast(
            long smeId,
            LocalDate openingDate,
            String openingAmount,
            String currency,
            LocalDate endDate,
            String horizonsParam,
            String modelParam) {
        BigDecimal parsedOpeningAmount = MoneyCodec.parseExact(openingAmount);
        String validatedCurrency = MoneyCodec.currency(currency);
        List<Integer> horizons = parseHorizons(horizonsParam);
        String model = validateModel(modelParam);

        CashFlowReport report = financialReportService.dailyCashFlow(
                smeId,
                new OpeningCash(openingDate, validatedCurrency, parsedOpeningAmount),
                endDate);

        List<PythonHistoryEntry> history = report.days().stream()
                .map(day -> new PythonHistoryEntry(day.date(), MoneyCodec.format(day.closingBalance())))
                .toList();

        List<PythonOpenInvoice> openInvoices = null;
        if (INVOICE_AWARE_MODELS.contains(model)) {
            InvoiceSummary invoiceSummary = financialReportService.invoiceSummary(smeId, endDate);
            openInvoices = invoiceSummary.invoices().stream()
                    .filter(position -> position.effectiveStatus() != PaymentStatus.CANCELLED
                            && position.outstanding().signum() > 0)
                    .map(position -> new PythonOpenInvoice(
                            position.direction().name(),
                            position.dueDate(),
                            MoneyCodec.format(position.outstanding())))
                    .toList();
        }

        PythonForecastResponse downstream = forecastClient.forecast(
                new PythonForecastRequest(validatedCurrency, history, horizons, model, openInvoices));

        return toSmeForecastResponse(smeId, openingDate, parsedOpeningAmount, endDate, downstream);
    }

    static List<Integer> parseHorizons(String raw) {
        if (raw == null) {
            return DEFAULT_HORIZONS;
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("horizons must contain comma-separated integers between 1 and 365");
        }
        String[] tokens = raw.split(",", -1);
        List<Integer> parsed = new ArrayList<>(tokens.length);
        Set<Integer> seen = new LinkedHashSet<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("horizons must not contain empty values");
            }
            final int horizon;
            try {
                horizon = Integer.parseInt(trimmed);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid horizon integer: " + trimmed, ex);
            }
            if (horizon < 1 || horizon > 365) {
                throw new IllegalArgumentException("Each horizon must be between 1 and 365");
            }
            if (!seen.add(horizon)) {
                throw new IllegalArgumentException("Duplicate horizon is not allowed: " + horizon);
            }
            parsed.add(horizon);
        }
        return List.copyOf(parsed);
    }

    static String validateModel(String raw) {
        if (raw == null) {
            return DEFAULT_MODEL;
        }
        String trimmed = raw.trim();
        if (!ALLOWED_MODELS.contains(trimmed)) {
            throw new IllegalArgumentException(
                    "Unsupported forecast model; allowed values: drift_calendar, ridge_lag_calendar, naive, gbdt_direct, invoice_aware_ml, ensemble_ml");
        }
        return trimmed;
    }

    private static SmeForecastResponse toSmeForecastResponse(
            long smeId,
            LocalDate openingDate,
            BigDecimal openingAmount,
            LocalDate endDate,
            PythonForecastResponse downstream) {
        PythonModelMetadata pm = downstream.model();
        if (pm == null || downstream.forecasts().isEmpty()) {
            throw new DownstreamServiceException(
                    503, "FORECAST_SERVICE_UNAVAILABLE", ForecastClient.SERVICE_UNAVAILABLE_MESSAGE);
        }
        ModelMetadata modelMetadata = new ModelMetadata(
                pm.name(),
                pm.version(),
                pm.description(),
                pm.intervalMethod(),
                pm.minHistoryDays(),
                pm.residualWindowMax(),
                pm.usesOpenInvoices(),
                pm.openInvoiceCount(),
                pm.activeMembers(),
                pm.ensembleWeights());

        List<HorizonForecast> forecasts = downstream.forecasts().stream()
                .map(ForecastService::toHorizonForecast)
                .toList();

        return new SmeForecastResponse(
                smeId,
                openingDate,
                MoneyCodec.format(openingAmount),
                endDate,
                downstream.currency(),
                downstream.originDate(),
                downstream.historyStartDate(),
                downstream.historyDays(),
                modelMetadata,
                forecasts,
                downstream.disclaimer());
    }

    private static HorizonForecast toHorizonForecast(PythonHorizonForecast item) {
        if (item.interval80() == null || item.interval95() == null) {
            throw new DownstreamServiceException(
                    503, "FORECAST_SERVICE_UNAVAILABLE", ForecastClient.SERVICE_UNAVAILABLE_MESSAGE);
        }
        return new HorizonForecast(
                item.horizonDays(),
                item.targetDate(),
                normalizeMoney(item.predictedClosingBalance()),
                new IntervalBounds(
                        normalizeMoney(item.interval80().lower()),
                        normalizeMoney(item.interval80().upper())),
                new IntervalBounds(
                        normalizeMoney(item.interval95().lower()),
                        normalizeMoney(item.interval95().upper())),
                item.residualCount());
    }

    private static String normalizeMoney(String value) {
        try {
            return MoneyCodec.format(MoneyCodec.parseExact(value));
        } catch (IllegalArgumentException ex) {
            throw new DownstreamServiceException(
                    503, "FORECAST_SERVICE_UNAVAILABLE", ForecastClient.SERVICE_UNAVAILABLE_MESSAGE, ex);
        }
    }
}
