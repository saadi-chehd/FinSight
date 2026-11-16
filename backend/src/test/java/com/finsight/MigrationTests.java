package com.finsight;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTests extends PostgresTestSupport {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway applicationFlyway;

    @Test
    void applicationMigrationsAreValidAndRerunIsNoOp() {
        applicationFlyway.validate();
        assertEquals(0, applicationFlyway.migrate().migrationsExecuted);
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
    }

    @Test
    void freshSchemaRepeatableChangeAndVersionedChecksumAreVerified(@TempDir Path dir) throws Exception {
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
        assertEquals(3, resources.length);
        for (var resource : resources) {
            try (var stream = resource.getInputStream()) { Files.copy(stream, dir.resolve(resource.getFilename())); }
        }
        Flyway isolated = Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
            .locations("filesystem:" + dir).cleanDisabled(true).load();
        try {
            assertEquals(3, isolated.migrate().migrationsExecuted);
            assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN ('smes','invoices','financial_transactions','scenarios','scenario_inputs')", Integer.class, schema));
            assertEquals(0, isolated.migrate().migrationsExecuted);
            Path repeatable = dir.resolve("R__schema_comments.sql");
            Files.writeString(repeatable, Files.readString(repeatable) + "\nCOMMENT ON TABLE smes IS 'Repeatable test metadata';\n");
            assertEquals(1, isolated.migrate().migrationsExecuted);
            assertEquals("Repeatable test metadata", jdbc.queryForObject("SELECT obj_description(?::regclass)", String.class, schema + ".smes"));
            Path versioned = dir.resolve("V1__financial_records.sql");
            Files.writeString(versioned, Files.readString(versioned) + "\n-- changed checksum in disposable test copy\n");
            assertFalse(isolated.validateWithResult().validationSuccessful);
        } finally {
            // Only this test-created schema, in the guarded test database, is removed.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
