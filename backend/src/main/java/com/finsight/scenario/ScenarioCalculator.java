package com.finsight.scenario;

import com.finsight.api.MoneyCodec;
import com.finsight.financial.FinancialCalculator;
import com.finsight.financial.FinancialModels.CashFlowReport;
import com.finsight.financial.FinancialModels.DailyCashFlow;
import com.finsight.financial.FinancialModels.Direction;
import com.finsight.financial.FinancialModels.Movement;
import com.finsight.financial.FinancialModels.OpeningCash;
import com.finsight.scenario.ScenarioModels.DatedMoney;
import com.finsight.scenario.ScenarioModels.Scenario;
import com.finsight.scenario.ScenarioModels.ScenarioInput;
import com.finsight.scenario.ScenarioModels.ScenarioKind;
import com.finsight.scenario.ScenarioModels.SimulatedDay;
import com.finsight.scenario.ScenarioModels.SimulationResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Pure deterministic scenario simulation over an explicit cash-flow window. */
@Component
public class ScenarioCalculator {
    public static final String DISCLAIMER =
        "Conditional arithmetic scenario comparison based solely on recorded transactions within the selected "
        + "window and user-supplied assumptions. This simulation is not a guarantee of future cash flows, "
        + "proof of solvency, or a credit or lending decision.";

    private static final int MAX_DELAY_DAYS = 3650;

    private final FinancialCalculator financialCalculator;

    public ScenarioCalculator(FinancialCalculator financialCalculator) {
        if (financialCalculator == null) {
            throw new IllegalArgumentException("FinancialCalculator is required");
        }
        this.financialCalculator = financialCalculator;
    }

    public SimulationResult simulate(
        long smeId,
        Scenario scenario,
        OpeningCash opening,
        LocalDate endDate,
        List<Movement> baselineMovements,
        BigDecimal warningThreshold,
        int maxDays
    ) {
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario is required");
        }
        if (scenario.smeId() != smeId) {
            throw new IllegalArgumentException("Scenario does not belong to SME");
        }
        if (scenario.kind() == null) {
            throw new IllegalArgumentException("Scenario kind is required");
        }

        CashFlowReport baselineReport = financialCalculator.cashFlow(
            smeId, opening, endDate, baselineMovements, warningThreshold, maxDays);

        List<String> notes = new ArrayList<>();
        List<Movement> simulatedMovements = buildSimulatedMovements(
            scenario, baselineReport.opening(), endDate, baselineMovements, notes);

        CashFlowReport scenarioReport = financialCalculator.cashFlow(
            smeId, baselineReport.opening(), endDate, simulatedMovements, baselineReport.warningThreshold(), maxDays);

        List<SimulatedDay> days = compareDays(baselineReport.days(), scenarioReport.days());
        DatedMoney baselineMin = minClosingBalance(baselineReport.days());
        DatedMoney scenarioMin = minClosingBalance(scenarioReport.days());
        BigDecimal minDelta = MoneyCodec.exact(scenarioMin.amount().subtract(baselineMin.amount()));

        return new SimulationResult(
            smeId,
            scenario,
            baselineReport.opening(),
            endDate,
            baselineReport.warningThreshold(),
            baselineMin,
            scenarioMin,
            minDelta,
            days,
            baselineReport.warnings(),
            scenarioReport.warnings(),
            notes,
            DISCLAIMER
        );
    }

    private List<Movement> buildSimulatedMovements(
        Scenario scenario,
        OpeningCash opening,
        LocalDate endDate,
        List<Movement> baselineMovements,
        List<String> notes
    ) {
        return switch (scenario.kind()) {
            case BASELINE -> {
                if (!scenario.inputs().isEmpty()) {
                    throw new IllegalArgumentException("BASELINE scenario must not contain inputs");
                }
                yield List.copyOf(baselineMovements);
            }
            case LATE_PAYMENT -> applyLatePayments(scenario.inputs(), opening.date(), endDate, baselineMovements, notes);
            case NEW_CONTRACT -> applyNewContracts(scenario.inputs(), opening, endDate, baselineMovements, notes);
        };
    }

    private List<Movement> applyLatePayments(
        List<ScenarioInput> inputs,
        LocalDate openingDate,
        LocalDate endDate,
        List<Movement> baselineMovements,
        List<String> notes
    ) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("LATE_PAYMENT scenario requires at least one input");
        }
        Map<Long, Integer> delayByInvoice = new LinkedHashMap<>();
        for (ScenarioInput input : inputs) {
            if (input == null || input.kind() != ScenarioKind.LATE_PAYMENT) {
                throw new IllegalArgumentException("Invalid LATE_PAYMENT input");
            }
            if (input.invoiceId() == null || input.invoiceId() <= 0) {
                throw new IllegalArgumentException("invoiceId must be positive");
            }
            if (input.delayDays() == null || input.delayDays() < 1 || input.delayDays() > MAX_DELAY_DAYS) {
                throw new IllegalArgumentException("delayDays must be between 1 and " + MAX_DELAY_DAYS);
            }
            if (delayByInvoice.putIfAbsent(input.invoiceId(), input.delayDays()) != null) {
                throw new IllegalArgumentException("Duplicate invoiceId in scenario inputs: " + input.invoiceId());
            }
        }

        Map<Long, Integer> matchedCounts = new HashMap<>();
        Map<Long, Integer> outOfWindowCounts = new HashMap<>();
        Map<Long, BigDecimal> outOfWindowTotals = new HashMap<>();
        List<Movement> simulated = new ArrayList<>(baselineMovements.size());

        for (Movement movement : baselineMovements) {
            boolean inWindow = !movement.date().isBefore(openingDate) && !movement.date().isAfter(endDate);
            Integer delayDays = (inWindow && movement.invoiceId() != null)
                ? delayByInvoice.get(movement.invoiceId())
                : null;

            if (delayDays == null) {
                simulated.add(movement);
                continue;
            }

            long invoiceId = movement.invoiceId();
            matchedCounts.merge(invoiceId, 1, Integer::sum);
            LocalDate shiftedDate = movement.date().plusDays(delayDays);
            if (shiftedDate.isAfter(endDate)) {
                outOfWindowCounts.merge(invoiceId, 1, Integer::sum);
                outOfWindowTotals.merge(invoiceId, movement.amount(), BigDecimal::add);
            } else {
                simulated.add(new Movement(
                    movement.id(),
                    movement.invoiceId(),
                    shiftedDate,
                    movement.currency(),
                    movement.direction(),
                    movement.amount()
                ));
            }
        }

        for (ScenarioInput input : inputs) {
            long invoiceId = input.invoiceId();
            int matched = matchedCounts.getOrDefault(invoiceId, 0);
            if (matched == 0) {
                notes.add("Invoice " + invoiceId + ": no recorded payments found in ["
                    + openingDate + ", " + endDate + "]; no cash movements were shifted or invented.");
            } else {
                int pastCount = outOfWindowCounts.getOrDefault(invoiceId, 0);
                if (pastCount > 0) {
                    BigDecimal pastTotal = MoneyCodec.exact(outOfWindowTotals.get(invoiceId));
                    notes.add("Invoice " + invoiceId + ": " + pastCount + " delayed payment(s) totaling "
                        + MoneyCodec.format(pastTotal) + " shifted past endDate " + endDate
                        + " and fall outside [" + openingDate + ", " + endDate + "].");
                }
            }
        }

        return simulated;
    }

    private List<Movement> applyNewContracts(
        List<ScenarioInput> inputs,
        OpeningCash opening,
        LocalDate endDate,
        List<Movement> baselineMovements,
        List<String> notes
    ) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("NEW_CONTRACT scenario requires at least one input");
        }
        Set<Long> usedIds = new HashSet<>(baselineMovements.size() + inputs.size());
        for (Movement movement : baselineMovements) {
            usedIds.add(movement.id());
        }

        List<Movement> simulated = new ArrayList<>(baselineMovements.size() + inputs.size());
        simulated.addAll(baselineMovements);

        long nextSyntheticId = Long.MAX_VALUE;
        for (ScenarioInput input : inputs) {
            if (input == null || input.kind() != ScenarioKind.NEW_CONTRACT) {
                throw new IllegalArgumentException("Invalid NEW_CONTRACT input");
            }
            if (input.expectedDate() == null) {
                throw new IllegalArgumentException("expectedDate is required for NEW_CONTRACT input");
            }
            if (input.currency() != null && !opening.currency().equals(input.currency())) {
                throw new IllegalArgumentException("Scenario input currency does not match opening currency");
            }
            BigDecimal amount = MoneyCodec.positive(input.amount());

            if (!input.expectedDate().isBefore(opening.date()) && !input.expectedDate().isAfter(endDate)) {
                while (usedIds.contains(nextSyntheticId)) {
                    nextSyntheticId--;
                    if (nextSyntheticId <= 0) {
                        throw new IllegalStateException("Exhausted synthetic movement IDs");
                    }
                }
                usedIds.add(nextSyntheticId);
                simulated.add(new Movement(
                    nextSyntheticId,
                    null,
                    input.expectedDate(),
                    opening.currency(),
                    Direction.INFLOW,
                    amount
                ));
            } else {
                notes.add("New contract inflow of " + MoneyCodec.format(amount) + " on "
                    + input.expectedDate() + " falls outside [" + opening.date() + ", " + endDate
                    + "] and was not applied.");
            }
        }

        return simulated;
    }

    private static List<SimulatedDay> compareDays(List<DailyCashFlow> baselineDays, List<DailyCashFlow> scenarioDays) {
        List<SimulatedDay> days = new ArrayList<>(baselineDays.size());
        for (int i = 0; i < baselineDays.size(); i++) {
            DailyCashFlow b = baselineDays.get(i);
            DailyCashFlow s = scenarioDays.get(i);
            BigDecimal delta = MoneyCodec.exact(s.closingBalance().subtract(b.closingBalance()));
            days.add(new SimulatedDay(
                b.date(),
                b.inflow(),
                b.outflow(),
                b.closingBalance(),
                s.inflow(),
                s.outflow(),
                s.closingBalance(),
                delta
            ));
        }
        return days;
    }

    private static DatedMoney minClosingBalance(List<DailyCashFlow> days) {
        DailyCashFlow minDay = days.get(0);
        for (int i = 1; i < days.size(); i++) {
            DailyCashFlow candidate = days.get(i);
            if (candidate.closingBalance().compareTo(minDay.closingBalance()) < 0) {
                minDay = candidate;
            }
        }
        return new DatedMoney(minDay.date(), minDay.closingBalance());
    }
}
