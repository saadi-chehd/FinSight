package com.finsight.scenario;

import com.finsight.scenario.ScenarioModels.NewScenarioInput;
import com.finsight.scenario.ScenarioModels.Scenario;
import com.finsight.scenario.ScenarioModels.ScenarioInput;
import com.finsight.scenario.ScenarioModels.ScenarioKind;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC repository for scenarios and scenario_inputs tables. */
@Repository
public class ScenarioRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<ScenarioHeader> SCENARIO_HEADER = (rs, rowNum) -> new ScenarioHeader(
        rs.getLong("id"),
        rs.getLong("sme_id"),
        rs.getString("name"),
        ScenarioKind.valueOf(rs.getString("kind")),
        rs.getObject("created_at", OffsetDateTime.class)
    );

    private static final RowMapper<ScenarioInput> SCENARIO_INPUT = (rs, rowNum) -> new ScenarioInput(
        rs.getLong("id"),
        rs.getLong("scenario_id"),
        rs.getLong("sme_id"),
        rs.getString("currency"),
        ScenarioKind.valueOf(rs.getString("kind")),
        rs.getObject("invoice_id", Long.class),
        rs.getObject("delay_days", Integer.class),
        rs.getBigDecimal("amount"),
        rs.getObject("expected_date", LocalDate.class)
    );

    public ScenarioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Scenario> findAllBySmeId(long smeId) {
        List<ScenarioHeader> headers = jdbc.sql("""
            SELECT id, sme_id, name, kind, created_at
            FROM scenarios
            WHERE sme_id = :smeId
            ORDER BY id
            """)
            .param("smeId", smeId)
            .query(SCENARIO_HEADER)
            .list();

        if (headers.isEmpty()) {
            return List.of();
        }

        List<ScenarioInput> allInputs = jdbc.sql("""
            SELECT id, scenario_id, sme_id, currency, kind, invoice_id, delay_days, amount, expected_date
            FROM scenario_inputs
            WHERE sme_id = :smeId
            ORDER BY id
            """)
            .param("smeId", smeId)
            .query(SCENARIO_INPUT)
            .list();

        Map<Long, List<ScenarioInput>> byScenario = new HashMap<>();
        for (ScenarioInput input : allInputs) {
            byScenario.computeIfAbsent(input.scenarioId(), ignored -> new ArrayList<>()).add(input);
        }

        return headers.stream()
            .map(h -> new Scenario(
                h.id(),
                h.smeId(),
                h.name(),
                h.kind(),
                h.createdAt(),
                byScenario.getOrDefault(h.id(), List.of())
            ))
            .toList();
    }

    public Optional<Scenario> findByIdAndSmeId(long smeId, long scenarioId) {
        Optional<ScenarioHeader> header = jdbc.sql("""
            SELECT id, sme_id, name, kind, created_at
            FROM scenarios
            WHERE id = :scenarioId AND sme_id = :smeId
            """)
            .param("scenarioId", scenarioId)
            .param("smeId", smeId)
            .query(SCENARIO_HEADER)
            .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<ScenarioInput> inputs = jdbc.sql("""
            SELECT id, scenario_id, sme_id, currency, kind, invoice_id, delay_days, amount, expected_date
            FROM scenario_inputs
            WHERE scenario_id = :scenarioId AND sme_id = :smeId
            ORDER BY id
            """)
            .param("scenarioId", scenarioId)
            .param("smeId", smeId)
            .query(SCENARIO_INPUT)
            .list();

        ScenarioHeader h = header.get();
        return Optional.of(new Scenario(h.id(), h.smeId(), h.name(), h.kind(), h.createdAt(), inputs));
    }

    public Set<Long> findOwnedInvoiceIds(long smeId, String currency, Set<Long> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return Set.of();
        }
        List<Long> found = jdbc.sql("""
            SELECT id
            FROM invoices
            WHERE sme_id = :smeId AND currency = :currency AND id IN (:invoiceIds)
            """)
            .param("smeId", smeId)
            .param("currency", currency)
            .param("invoiceIds", invoiceIds)
            .query(Long.class)
            .list();
        return new HashSet<>(found);
    }

    public Scenario createScenario(
        long smeId,
        String name,
        ScenarioKind kind,
        String currency,
        List<NewScenarioInput> inputs
    ) {
        ScenarioHeader header = jdbc.sql("""
            INSERT INTO scenarios (sme_id, name, kind)
            VALUES (:smeId, :name, :kind)
            RETURNING id, sme_id, name, kind, created_at
            """)
            .param("smeId", smeId)
            .param("name", name)
            .param("kind", kind.name())
            .query(SCENARIO_HEADER)
            .single();

        List<ScenarioInput> createdInputs = new ArrayList<>(inputs.size());
        for (NewScenarioInput input : inputs) {
            ScenarioInput inserted = jdbc.sql("""
                INSERT INTO scenario_inputs (
                    scenario_id, sme_id, currency, kind, invoice_id, delay_days, amount, expected_date
                )
                VALUES (
                    :scenarioId, :smeId, :currency, :kind, :invoiceId, :delayDays, :amount, :expectedDate
                )
                RETURNING id, scenario_id, sme_id, currency, kind, invoice_id, delay_days, amount, expected_date
                """)
                .param("scenarioId", header.id())
                .param("smeId", smeId)
                .param("currency", currency)
                .param("kind", kind.name())
                .param("invoiceId", input.invoiceId(), Types.BIGINT)
                .param("delayDays", input.delayDays(), Types.INTEGER)
                .param("amount", input.amount(), Types.NUMERIC)
                .param("expectedDate", input.expectedDate(), Types.DATE)
                .query(SCENARIO_INPUT)
                .single();
            createdInputs.add(inserted);
        }

        return new Scenario(
            header.id(),
            header.smeId(),
            header.name(),
            header.kind(),
            header.createdAt(),
            createdInputs
        );
    }

    public boolean deleteScenario(long smeId, long scenarioId) {
        jdbc.sql("DELETE FROM scenario_inputs WHERE scenario_id = :scenarioId AND sme_id = :smeId")
            .param("scenarioId", scenarioId)
            .param("smeId", smeId)
            .update();
        int deleted = jdbc.sql("DELETE FROM scenarios WHERE id = :scenarioId AND sme_id = :smeId")
            .param("scenarioId", scenarioId)
            .param("smeId", smeId)
            .update();
        return deleted > 0;
    }

    private record ScenarioHeader(
        long id,
        long smeId,
        String name,
        ScenarioKind kind,
        OffsetDateTime createdAt
    ) { }
}
