package com.finsight;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
public abstract class PostgresTestSupport {
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        String url = required("TEST_DB_URL");
        String user = required("TEST_DB_USER");
        // Fail before Spring/Flyway can connect to an accidental development database.
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/finsight_test")
                || !user.equals("finsight_test_app")) {
            throw new IllegalStateException("Tests require loopback finsight_test and finsight_test_app");
        }
        String password = required("TEST_DB_PASSWORD");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name + " for real PostgreSQL tests");
        return value;
    }
}
