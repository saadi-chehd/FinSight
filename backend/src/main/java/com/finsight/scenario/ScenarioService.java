package com.finsight.scenario;

import com.finsight.api.MoneyCodec;
import com.finsight.api.ResourceNotFoundException;
import com.finsight.financial.FinancialModels.Movement;
import com.finsight.financial.FinancialModels.OpeningCash;
import com.finsight.financial.FinancialReadRepository;
import com.finsight.scenario.ScenarioModels.CreateScenarioInputRequest;
import com.finsight.scenario.ScenarioModels.CreateScenarioRequest;
import com.finsight.scenario.ScenarioModels.NewScenarioInput;
import com.finsight.scenario.ScenarioModels.Scenario;
import com.finsight.scenario.ScenarioModels.ScenarioKind;
import com.finsight.scenario.ScenarioModels.ScenarioResponse;
import com.finsight.scenario.ScenarioModels.ScenarioSimulationResponse;
import com.finsight.scenario.ScenarioModels.SimulateScenarioRequest;
import com.finsight.scenario.ScenarioModels.SimulationResult;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates scenario CRUD and read-only repeatable-read simulations. */
@Service
public class ScenarioService {
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_DELAY_DAYS = 3650;

    private final ScenarioRepository scenarioRepository;
    private final FinancialReadRepository financialReadRepository;
    private final ScenarioCalculator scenarioCalculator;
    private final BigDecimal threshold;
    private final int maxDays;

    public ScenarioService(
        ScenarioRepository scenarioRepository,
        FinancialReadRepository financialReadRepository,
        ScenarioCalculator scenarioCalculator,
        @Value("${financial.liquidity-warning-threshold}") BigDecimal threshold,
        @Value("${financial.max-report-days}") int maxDays
    ) {
        this.scenarioRepository = scenarioRepository;
        this.financialReadRepository = financialReadRepository;
        this.scenarioCalculator = scenarioCalculator;
        this.threshold = MoneyCodec.exact(threshold);
        if (this.threshold.signum() < 0) {
            throw new IllegalArgumentException("Liquidity threshold must be nonnegative");
        }
        if (maxDays < 1) {
            throw new IllegalArgumentException("Maximum report days must be positive");
        }
        this.maxDays = maxDays;
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> listScenarios(long smeId) {
        validatePositiveId(smeId, "SME ID");
        financialReadRepository.currency(smeId);
        return scenarioRepository.findAllBySmeId(smeId).stream()
            .map(ScenarioModels::toResponse)
            .toList();
    }

    @Transactional
    public ScenarioResponse createScenario(long smeId, CreateScenarioRequest request) {
        validatePositiveId(smeId, "SME ID");
        if (request == null) {
            throw new IllegalArgumentException("Scenario payload is required");
        }
        String name = validateName(request.name());
        ScenarioKind kind = parseKind(request.kind());
        List<CreateScenarioInputRequest> rawInputs = request.inputs() == null ? List.of() : request.inputs();

        List<NewScenarioInput> validatedInputs = validateInputs(kind, rawInputs);
        String currency = financialReadRepository.currency(smeId);

        if (kind == ScenarioKind.LATE_PAYMENT) {
            Set<Long> requestedInvoiceIds = new HashSet<>(validatedInputs.size());
            for (NewScenarioInput input : validatedInputs) {
                requestedInvoiceIds.add(input.invoiceId());
            }
            Set<Long> ownedInvoiceIds = scenarioRepository.findOwnedInvoiceIds(smeId, currency, requestedInvoiceIds);
            for (NewScenarioInput input : validatedInputs) {
                if (!ownedInvoiceIds.contains(input.invoiceId())) {
                    throw new ResourceNotFoundException("Invoice not found for SME: " + input.invoiceId());
                }
            }
        }

        Scenario created = scenarioRepository.createScenario(smeId, name, kind, currency, validatedInputs);
        return ScenarioModels.toResponse(created);
    }

    @Transactional
    public void deleteScenario(long smeId, long scenarioId) {
        validatePositiveId(smeId, "SME ID");
        validatePositiveId(scenarioId, "Scenario ID");
        financialReadRepository.currency(smeId);
        if (!scenarioRepository.deleteScenario(smeId, scenarioId)) {
            throw new ResourceNotFoundException("Unknown scenario");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScenarioSimulationResponse simulateScenario(long smeId, long scenarioId, SimulateScenarioRequest request) {
        validatePositiveId(smeId, "SME ID");
        validatePositiveId(scenarioId, "Scenario ID");
        if (request == null || request.openingDate() == null || request.endDate() == null) {
            throw new IllegalArgumentException("openingDate, openingAmount, currency, and endDate are required");
        }
        BigDecimal openingAmount = MoneyCodec.parseExact(request.openingAmount());
        String currency = MoneyCodec.currency(request.currency());
        long dayCount = ChronoUnit.DAYS.between(request.openingDate(), request.endDate()) + 1;
        if (dayCount < 1 || dayCount > maxDays) {
            throw new IllegalArgumentException("Invalid or oversized date range");
        }

        String smeCurrency = financialReadRepository.currency(smeId);
        if (!smeCurrency.equals(currency)) {
            throw new IllegalArgumentException("Opening cash currency does not match SME");
        }

        Scenario scenario = scenarioRepository.findByIdAndSmeId(smeId, scenarioId)
            .orElseThrow(() -> new ResourceNotFoundException("Unknown scenario"));

        List<Movement> movements = financialReadRepository.movements(smeId, request.openingDate(), request.endDate());
        SimulationResult result = scenarioCalculator.simulate(
            smeId,
            scenario,
            new OpeningCash(request.openingDate(), currency, openingAmount),
            request.endDate(),
            movements,
            threshold,
            maxDays
        );
        return ScenarioModels.toResponse(result);
    }

    private static void validatePositiveId(long id, String label) {
        if (id <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Scenario name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Scenario name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private static ScenarioKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Scenario kind is required");
        }
        try {
            return ScenarioKind.valueOf(kind.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported scenario kind");
        }
    }

    private static List<NewScenarioInput> validateInputs(
        ScenarioKind kind,
        List<CreateScenarioInputRequest> rawInputs
    ) {
        for (CreateScenarioInputRequest item : rawInputs) {
            if (item == null) {
                throw new IllegalArgumentException("Scenario input item cannot be null");
            }
        }

        return switch (kind) {
            case BASELINE -> {
                if (!rawInputs.isEmpty()) {
                    throw new IllegalArgumentException("BASELINE scenario must not contain inputs");
                }
                yield List.of();
            }
            case LATE_PAYMENT -> {
                if (rawInputs.isEmpty()) {
                    throw new IllegalArgumentException("LATE_PAYMENT scenario requires at least one input");
                }
                Set<Long> seenInvoiceIds = new HashSet<>(rawInputs.size());
                List<NewScenarioInput> validated = new ArrayList<>(rawInputs.size());
                for (CreateScenarioInputRequest item : rawInputs) {
                    if (item.invoiceId() == null || item.invoiceId() <= 0) {
                        throw new IllegalArgumentException("invoiceId must be positive");
                    }
                    if (item.delayDays() == null || item.delayDays() < 1 || item.delayDays() > MAX_DELAY_DAYS) {
                        throw new IllegalArgumentException("delayDays must be between 1 and " + MAX_DELAY_DAYS);
                    }
                    if (item.amount() != null || item.expectedDate() != null) {
                        throw new IllegalArgumentException("LATE_PAYMENT inputs must not specify amount or expectedDate");
                    }
                    if (!seenInvoiceIds.add(item.invoiceId())) {
                        throw new IllegalArgumentException("Duplicate invoiceId in scenario inputs: " + item.invoiceId());
                    }
                    validated.add(new NewScenarioInput(item.invoiceId(), item.delayDays(), null, null));
                }
                yield validated;
            }
            case NEW_CONTRACT -> {
                if (rawInputs.isEmpty()) {
                    throw new IllegalArgumentException("NEW_CONTRACT scenario requires at least one input");
                }
                List<NewScenarioInput> validated = new ArrayList<>(rawInputs.size());
                for (CreateScenarioInputRequest item : rawInputs) {
                    if (item.invoiceId() != null || item.delayDays() != null) {
                        throw new IllegalArgumentException("NEW_CONTRACT inputs must not specify invoiceId or delayDays");
                    }
                    BigDecimal amount = MoneyCodec.parsePositive(item.amount());
                    if (item.expectedDate() == null) {
                        throw new IllegalArgumentException("expectedDate is required for NEW_CONTRACT input");
                    }
                    validated.add(new NewScenarioInput(null, null, amount, item.expectedDate()));
                }
                yield validated;
            }
        };
    }
}
