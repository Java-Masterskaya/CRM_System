package ru.practicum.crm.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

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
class ActuatorHealthIT {

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
    void livenessAndReadinessAreUp_whenDbIsUp() {
        assertThat(get("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/health/readiness").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aggregateIsUp_whenAllComponentsAreUp() {
        ResponseEntity<String> response = get("/actuator/health");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusIsExposed_andSensitiveEndpointsAreNot() {
        assertThat(get("/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get("/actuator/env").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/actuator/beans").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/actuator/configprops").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/actuator/heapdump").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/actuator/threaddump").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity("http://localhost:" + managementPort + path, String.class);
    }
}
