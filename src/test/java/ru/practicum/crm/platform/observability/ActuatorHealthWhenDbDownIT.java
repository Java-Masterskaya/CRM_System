package ru.practicum.crm.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = "management.health.mail.enabled=false")
class ActuatorHealthWhenDbDownIT {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @LocalManagementPort
    int managementPort;

    @Test
    void livenessStaysUpAndReadinessGoesDown_whenDbIsDown() {
        postgres.stop();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(get("/actuator/health/readiness").getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        long start = System.nanoTime();
        get("/actuator/health/readiness");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("readiness не должна висеть дольше connection-timeout")
                .isLessThan(5_000);

        assertThat(get("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity("http://localhost:" + managementPort + path, String.class);
    }
}
