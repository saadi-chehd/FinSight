package com.finsight.scenario;

import com.finsight.api.MoneyCodec;
import com.finsight.financial.FinancialModels.LiquidityWarning;
import com.finsight.financial.FinancialModels.OpeningCash;
import com.finsight.financial.FinancialModels.WarningKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Models, request/response DTOs, and formatting mappers for scenario analysis. */
public final class ScenarioModels {
    private ScenarioModels() { }

    public enum ScenarioKind {
        BASELINE,
        LATE_PAYMENT,
        NEW_CONTRACT
    }

    public record ScenarioInput(
        long id,
        long scenarioId,
        long smeId,
        String currency,
        ScenarioKind kind,
        Long invoiceId,
        Integer delayDays,
        BigDecimal amount,
        LocalDate expectedDate
    ) { }

    public record Scenario(
        long id,
        long smeId,
        String name,
        ScenarioKind kind,
        OffsetDateTime createdAt,
        List<ScenarioInput> inputs
    ) {
        public Scenario {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }

    public record NewScenarioInput(
        Long invoiceId,
        Integer delayDays,
        BigDecimal amount,
        LocalDate expectedDate
    ) { }

    public record DatedMoney(LocalDate date, BigDecimal amount) { }

    public record SimulatedDay(
        LocalDate date,
        BigDecimal baselineInflow,
        BigDecimal baselineOutflow,
        BigDecimal baselineClosingBalance,
        BigDecimal scenarioInflow,
        BigDecimal scenarioOutflow,
        BigDecimal scenarioClosingBalance,
        BigDecimal closingBalanceDelta
    ) { }

    public record SimulationResult(
        long smeId,
        Scenario scenario,
        OpeningCash opening,
        LocalDate endDate,
        BigDecimal warningThreshold,
        DatedMoney baselineMinClosingBalance,
        DatedMoney scenarioMinClosingBalance,
        BigDecimal minClosingBalanceDelta,
        List<SimulatedDay> days,
        List<LiquidityWarning> baselineWarnings,
        List<LiquidityWarning> scenarioWarnings,
        List<String> notes,
        String disclaimer
    ) {
        public SimulationResult {
            days = List.copyOf(days);
            baselineWarnings = List.copyOf(baselineWarnings);
            scenarioWarnings = List.copyOf(scenarioWarnings);
            notes = List.copyOf(notes);
        }
    }

    public record CreateScenarioRequest(
        String name,
        String kind,
        List<CreateScenarioInputRequest> inputs
    ) { }

    public record CreateScenarioInputRequest(
        Long invoiceId,
        Integer delayDays,
        String amount,
        LocalDate expectedDate
    ) { }

    public record SimulateScenarioRequest(
        LocalDate openingDate,
        String openingAmount,
        String currency,
        LocalDate endDate
    ) { }

    public record ScenarioInputResponse(
        long id,
        Long invoiceId,
        Integer delayDays,
        String amount,
        LocalDate expectedDate
    ) { }

    public record ScenarioResponse(
        long id,
        long smeId,
        String name,
        ScenarioKind kind,
        OffsetDateTime createdAt,
        List<ScenarioInputResponse> inputs
    ) { }

    public record OpeningCashResponse(
        LocalDate date,
        String currency,
        String amount
    ) { }

    public record DatedMoneyResponse(
        LocalDate date,
        String amount
    ) { }

    public record SimulatedDayResponse(
        LocalDate date,
        String baselineInflow,
        String baselineOutflow,
        String baselineClosingBalance,
        String scenarioInflow,
        String scenarioOutflow,
        String scenarioClosingBalance,
        String closingBalanceDelta
    ) { }

    public record LiquidityWarningResponse(
        LocalDate date,
        WarningKind kind,
        String closingBalance,
        String threshold,
        String gapToThreshold
    ) { }

    public record ScenarioSimulationResponse(
        long smeId,
        ScenarioResponse scenario,
        OpeningCashResponse opening,
        LocalDate endDate,
        String warningThreshold,
        DatedMoneyResponse baselineMinClosingBalance,
        DatedMoneyResponse scenarioMinClosingBalance,
        String minClosingBalanceDelta,
        List<SimulatedDayResponse> days,
        List<LiquidityWarningResponse> baselineWarnings,
        List<LiquidityWarningResponse> scenarioWarnings,
        List<String> notes,
        String disclaimer
    ) { }

    public static ScenarioResponse toResponse(Scenario scenario) {
        List<ScenarioInputResponse> inputResponses = scenario.inputs().stream()
            .map(input -> new ScenarioInputResponse(
                input.id(),
                input.invoiceId(),
                input.delayDays(),
                input.amount() == null ? null : MoneyCodec.format(input.amount()),
                input.expectedDate()
            ))
            .toList();
        return new ScenarioResponse(
            scenario.id(),
            scenario.smeId(),
            scenario.name(),
            scenario.kind(),
            scenario.createdAt(),
            inputResponses
        );
    }

    public static ScenarioSimulationResponse toResponse(SimulationResult result) {
        return new ScenarioSimulationResponse(
            result.smeId(),
            toResponse(result.scenario()),
            new OpeningCashResponse(
                result.opening().date(),
                result.opening().currency(),
                MoneyCodec.format(result.opening().amount())
            ),
            result.endDate(),
            MoneyCodec.format(result.warningThreshold()),
            new DatedMoneyResponse(
                result.baselineMinClosingBalance().date(),
                MoneyCodec.format(result.baselineMinClosingBalance().amount())
            ),
            new DatedMoneyResponse(
                result.scenarioMinClosingBalance().date(),
                MoneyCodec.format(result.scenarioMinClosingBalance().amount())
            ),
            MoneyCodec.format(result.minClosingBalanceDelta()),
            result.days().stream()
                .map(day -> new SimulatedDayResponse(
                    day.date(),
                    MoneyCodec.format(day.baselineInflow()),
                    MoneyCodec.format(day.baselineOutflow()),
                    MoneyCodec.format(day.baselineClosingBalance()),
                    MoneyCodec.format(day.scenarioInflow()),
                    MoneyCodec.format(day.scenarioOutflow()),
                    MoneyCodec.format(day.scenarioClosingBalance()),
                    MoneyCodec.format(day.closingBalanceDelta())
                ))
                .toList(),
            result.baselineWarnings().stream().map(ScenarioModels::toWarningResponse).toList(),
            result.scenarioWarnings().stream().map(ScenarioModels::toWarningResponse).toList(),
            result.notes(),
            result.disclaimer()
        );
    }

    private static LiquidityWarningResponse toWarningResponse(LiquidityWarning warning) {
        return new LiquidityWarningResponse(
            warning.date(),
            warning.kind(),
            MoneyCodec.format(warning.closingBalance()),
            MoneyCodec.format(warning.threshold()),
            MoneyCodec.format(warning.gapToThreshold())
        );
    }
}
