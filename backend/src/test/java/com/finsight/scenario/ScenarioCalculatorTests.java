package com.finsight.scenario;

import com.finsight.financial.FinancialCalculator;
import com.finsight.financial.FinancialModels.Direction;
import com.finsight.financial.FinancialModels.Movement;
import com.finsight.financial.FinancialModels.OpeningCash;
import com.finsight.financial.FinancialModels.WarningKind;
import com.finsight.scenario.ScenarioModels.Scenario;
import com.finsight.scenario.ScenarioModels.ScenarioInput;
import com.finsight.scenario.ScenarioModels.ScenarioKind;
import com.finsight.scenario.ScenarioModels.SimulationResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioCalculatorTests {
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private final ScenarioCalculator calculator = new ScenarioCalculator(new FinancialCalculator());
    private final LocalDate start = LocalDate.of(2026, 1, 10);

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private OpeningCash opening(String amount) {
        return new OpeningCash(start, "USD", money(amount));
    }

    private Movement movement(long id, int dayOffset, Direction direction, String amount, Long invoiceId) {
        return new Movement(id, invoiceId, start.plusDays(dayOffset), "USD", direction, money(amount));
    }

    private Scenario scenario(ScenarioKind kind, List<ScenarioInput> inputs) {
        return new Scenario(1L, 1L, "Test " + kind, kind, CREATED_AT, inputs);
    }

    private ScenarioInput lateInput(long id, long invoiceId, int delayDays) {
        return new ScenarioInput(id, 1L, 1L, "USD", ScenarioKind.LATE_PAYMENT, invoiceId, delayDays, null, null);
    }

    private ScenarioInput contractInput(long id, String amount, LocalDate expectedDate) {
        return new ScenarioInput(id, 1L, 1L, "USD", ScenarioKind.NEW_CONTRACT, null, null, money(amount), expectedDate);
    }

    @Test
    void baselineScenarioMatchesBaselineCashFlowWithZeroDeltasAndNoNotes() {
        List<Movement> movements = List.of(
            movement(1L, 0, Direction.INFLOW, "50.00", 10L),
            movement(2L, 1, Direction.OUTFLOW, "70.00", null)
        );
        SimulationResult result = calculator.simulate(
            1L, scenario(ScenarioKind.BASELINE, List.of()), opening("100.00"),
            start.plusDays(2), movements, money("90.00"), 31
        );

        assertEquals(3, result.days().size());
        assertEquals(money("80.00"), result.baselineMinClosingBalance().amount());
        assertEquals(start.plusDays(1), result.baselineMinClosingBalance().date());
        assertEquals(result.baselineMinClosingBalance(), result.scenarioMinClosingBalance());
        assertEquals(money("0.00"), result.minClosingBalanceDelta());
        for (var day : result.days()) {
            assertEquals(day.baselineInflow(), day.scenarioInflow());
            assertEquals(day.baselineOutflow(), day.scenarioOutflow());
            assertEquals(day.baselineClosingBalance(), day.scenarioClosingBalance());
            assertEquals(money("0.00"), day.closingBalanceDelta());
        }
        assertEquals(2, result.baselineWarnings().size());
        assertEquals(result.baselineWarnings(), result.scenarioWarnings());
        assertTrue(result.notes().isEmpty());
        assertTrue(result.disclaimer().toLowerCase().contains("conditional arithmetic"));
        assertTrue(result.disclaimer().toLowerCase().contains("not a guarantee"));
    }

    @Test
    void latePaymentWithinWindowShiftsCashAndCreatesNegativeThenZeroDelta() {
        List<Movement> movements = List.of(
            movement(1L, 0, Direction.INFLOW, "100.00", 11L),
            movement(2L, 1, Direction.OUTFLOW, "150.00", null)
        );
        SimulationResult result = calculator.simulate(
            1L, scenario(ScenarioKind.LATE_PAYMENT, List.of(lateInput(1L, 11L, 2))),
            opening("100.00"), start.plusDays(2), movements, money("0.00"), 31
        );

        // Day 0: baseline=200.00, scenario=100.00 -> delta=-100.00
        assertEquals(money("200.00"), result.days().get(0).baselineClosingBalance());
        assertEquals(money("100.00"), result.days().get(0).scenarioClosingBalance());
        assertEquals(money("-100.00"), result.days().get(0).closingBalanceDelta());

        // Day 1: baseline=50.00, scenario=-50.00 -> delta=-100.00 (shortfall warning only in scenario)
        assertEquals(money("50.00"), result.days().get(1).baselineClosingBalance());
        assertEquals(money("-50.00"), result.days().get(1).scenarioClosingBalance());
        assertEquals(money("-100.00"), result.days().get(1).closingBalanceDelta());

        // Day 2: delayed inflow arrives -> baseline=50.00, scenario=50.00 -> delta=0.00
        assertEquals(money("100.00"), result.days().get(2).scenarioInflow());
        assertEquals(money("50.00"), result.days().get(2).scenarioClosingBalance());
        assertEquals(money("0.00"), result.days().get(2).closingBalanceDelta());

        assertEquals(money("50.00"), result.baselineMinClosingBalance().amount());
        assertEquals(money("-50.00"), result.scenarioMinClosingBalance().amount());
        assertEquals(start.plusDays(1), result.scenarioMinClosingBalance().date());
        assertEquals(money("-100.00"), result.minClosingBalanceDelta());

        assertTrue(result.baselineWarnings().isEmpty());
        assertEquals(1, result.scenarioWarnings().size());
        assertEquals(WarningKind.POTENTIAL_CASH_SHORTFALL, result.scenarioWarnings().get(0).kind());
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void latePaymentShiftedPastEndDateEmitsNoteAndExcludesDelayedCashFromWindow() {
        List<Movement> movements = List.of(
            movement(1L, 1, Direction.INFLOW, "40.25", 12L),
            movement(2L, 2, Direction.INFLOW, "59.75", 12L)
        );
        SimulationResult result = calculator.simulate(
            1L, scenario(ScenarioKind.LATE_PAYMENT, List.of(lateInput(1L, 12L, 2))),
            opening("10.00"), start.plusDays(2), movements, money("0.00"), 31
        );

        // Day 1 + 2 = Day 3 (> Day 2 endDate); Day 2 + 2 = Day 4 (> Day 2 endDate)
        assertEquals(money("110.00"), result.days().get(2).baselineClosingBalance());
        assertEquals(money("10.00"), result.days().get(2).scenarioClosingBalance());
        assertEquals(money("-100.00"), result.days().get(2).closingBalanceDelta());

        assertEquals(1, result.notes().size());
        String note = result.notes().get(0);
        assertTrue(note.contains("Invoice 12"));
        assertTrue(note.contains("100.00"));
        assertTrue(note.contains(start.plusDays(2).toString()));
    }

    @Test
    void latePaymentWithNoLinkedPaymentsInWindowEmitsNoteWithoutInventingCash() {
        List<Movement> movements = List.of(
            movement(1L, -1, Direction.INFLOW, "500.00", 13L),
            movement(2L, 0, Direction.INFLOW, "20.00", null)
        );
        SimulationResult result = calculator.simulate(
            1L, scenario(ScenarioKind.LATE_PAYMENT, List.of(lateInput(1L, 13L, 1))),
            opening("100.00"), start.plusDays(1), movements, money("0.00"), 31
        );

        // Pre-window movement is NOT shifted into [start, start+1]
        assertEquals(money("120.00"), result.days().get(1).baselineClosingBalance());
        assertEquals(money("120.00"), result.days().get(1).scenarioClosingBalance());
        assertEquals(money("0.00"), result.minClosingBalanceDelta());
        assertEquals(1, result.notes().size());
        assertTrue(result.notes().get(0).contains("Invoice 13"));
        assertTrue(result.notes().get(0).contains("no recorded payments"));
    }

    @Test
    void newContractWithinWindowAddsInflowAndHandlesMaxLongExistingMovementId() {
        List<Movement> movements = List.of(
            movement(Long.MAX_VALUE, 0, Direction.OUTFLOW, "30.00", null)
        );
        SimulationResult result = calculator.simulate(
            1L,
            scenario(ScenarioKind.NEW_CONTRACT, List.of(
                contractInput(1L, "45.10", start.plusDays(1)),
                contractInput(2L, "0.20", start.plusDays(1))
            )),
            opening("50.00"), start.plusDays(2), movements, money("25.00"), 31
        );

        assertEquals(money("20.00"), result.baselineMinClosingBalance().amount());
        assertEquals(money("20.00"), result.scenarioMinClosingBalance().amount());
        assertEquals(money("0.00"), result.minClosingBalanceDelta());

        assertEquals(money("45.30"), result.days().get(1).scenarioInflow());
        assertEquals(money("65.30"), result.days().get(1).scenarioClosingBalance());
        assertEquals(money("45.30"), result.days().get(1).closingBalanceDelta());
        assertEquals(money("65.30"), result.days().get(2).scenarioClosingBalance());
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void newContractOutsideWindowEmitsNoteWithoutApplyingCash() {
        SimulationResult result = calculator.simulate(
            1L,
            scenario(ScenarioKind.NEW_CONTRACT, List.of(
                contractInput(1L, "250.00", start.minusDays(1)),
                contractInput(2L, "300.00", start.plusDays(3))
            )),
            opening("100.00"), start.plusDays(2), List.of(), money("0.00"), 31
        );

        for (var day : result.days()) {
            assertEquals(money("100.00"), day.scenarioClosingBalance());
            assertEquals(money("0.00"), day.closingBalanceDelta());
        }
        assertEquals(2, result.notes().size());
        assertTrue(result.notes().get(0).contains("250.00"));
        assertTrue(result.notes().get(1).contains("300.00"));
    }

    @Test
    void precisionCheckAndEarliestTieBreakerOnMinimumClosingBalance() {
        List<Movement> movements = List.of(
            movement(1L, 0, Direction.INFLOW, "0.10", null),
            movement(2L, 0, Direction.INFLOW, "0.20", null),
            movement(3L, 2, Direction.OUTFLOW, "0.30", null)
        );
        SimulationResult result = calculator.simulate(
            1L,
            scenario(ScenarioKind.NEW_CONTRACT, List.of(contractInput(1L, "0.05", start.plusDays(1)))),
            opening("1.00"), start.plusDays(2), movements, money("0.00"), 31
        );

        // Baseline: Day0=1.30, Day1=1.30, Day2=1.00 -> min=1.00 on Day2
        assertEquals(money("1.00"), result.baselineMinClosingBalance().amount());
        assertEquals(start.plusDays(2), result.baselineMinClosingBalance().date());

        // Scenario: Day0=1.30, Day1=1.35, Day2=1.05 -> min=1.05 on Day2
        assertEquals(money("1.05"), result.scenarioMinClosingBalance().amount());
        assertEquals(money("0.05"), result.minClosingBalanceDelta());

        // Tie test with zero movements: earliest day (Day 0) wins
        SimulationResult flat = calculator.simulate(
            1L, scenario(ScenarioKind.BASELINE, List.of()), opening("10.00"),
            start.plusDays(2), List.of(), money("0.00"), 31
        );
        assertEquals(start, flat.baselineMinClosingBalance().date());
        assertEquals(start, flat.scenarioMinClosingBalance().date());
    }

    @Test
    void rejectsInvalidScenarioOrMismatchedOwnership() {
        assertThrows(IllegalArgumentException.class, () ->
            calculator.simulate(1L, null, opening("10.00"), start, List.of(), money("0.00"), 31));
        assertThrows(IllegalArgumentException.class, () ->
            calculator.simulate(2L, scenario(ScenarioKind.BASELINE, List.of()), opening("10.00"), start, List.of(), money("0.00"), 31));
        assertThrows(IllegalArgumentException.class, () ->
            calculator.simulate(1L, scenario(ScenarioKind.BASELINE, List.of(lateInput(1L, 1L, 1))), opening("10.00"), start, List.of(), money("0.00"), 31));
        assertThrows(IllegalArgumentException.class, () ->
            calculator.simulate(1L, scenario(ScenarioKind.LATE_PAYMENT, List.of()), opening("10.00"), start, List.of(), money("0.00"), 31));
        assertThrows(IllegalArgumentException.class, () ->
            calculator.simulate(1L, scenario(ScenarioKind.LATE_PAYMENT, List.of(lateInput(1L, 10L, 1), lateInput(2L, 10L, 2))), opening("10.00"), start, List.of(), money("0.00"), 31));
        assertThrows(IllegalArgumentException.class, () ->
            calculator.simulate(1L, scenario(ScenarioKind.NEW_CONTRACT, List.of()), opening("10.00"), start, List.of(), money("0.00"), 31));
        assertThrows(IllegalArgumentException.class, () ->
            calculator.simulate(1L, scenario(ScenarioKind.NEW_CONTRACT, List.of(contractInput(1L, "1.005", start))), opening("10.00"), start, List.of(), money("0.00"), 31));
    }
}
