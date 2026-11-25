package com.finsight.scenario;

import com.finsight.scenario.ScenarioModels.CreateScenarioRequest;
import com.finsight.scenario.ScenarioModels.ScenarioResponse;
import com.finsight.scenario.ScenarioModels.ScenarioSimulationResponse;
import com.finsight.scenario.ScenarioModels.SimulateScenarioRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for creating, listing, deleting, and simulating SME cash-flow scenarios. */
@RestController
@RequestMapping("/api/smes/{smeId}/scenarios")
public class ScenarioController {
    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping
    public List<ScenarioResponse> list(@PathVariable long smeId) {
        return scenarioService.listScenarios(smeId);
    }

    @PostMapping
    public ResponseEntity<ScenarioResponse> create(
        @PathVariable long smeId,
        @RequestBody CreateScenarioRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scenarioService.createScenario(smeId, request));
    }

    @DeleteMapping("/{scenarioId}")
    public ResponseEntity<Void> delete(@PathVariable long smeId, @PathVariable long scenarioId) {
        scenarioService.deleteScenario(smeId, scenarioId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{scenarioId}/simulate")
    public ScenarioSimulationResponse simulate(
        @PathVariable long smeId,
        @PathVariable long scenarioId,
        @RequestBody SimulateScenarioRequest request
    ) {
        return scenarioService.simulateScenario(smeId, scenarioId, request);
    }
}
