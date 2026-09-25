package ru.practicum.crm.platform.observability;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.error.GlobalExceptionHandler;
import ru.practicum.crm.platform.web.RequestIdFilter;

@WebMvcTest(
        controllers = StructuredLoggingTest.ProbeConfiguration.ProbeController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@Import(GlobalExceptionHandler.class)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_TOKEN = "eyJhbGciOiJIUzI1NiJ9.very-secret-token";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void log_whenWrittenDuringRequest_isJsonWithLevelAndRequestId(CapturedOutput output)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/logging-probe/hello"))
                .andExpect(status().isOk())
                .andReturn();
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);

        JsonNode record = findRecord(output, "Проба логирования");
        assertThat(record.has("@timestamp")).isTrue();
        assertThat(record.get("level").asText()).isEqualTo("INFO");
        assertThat(record.get("logger_name").asText())
                .isEqualTo(ProbeConfiguration.ProbeController.class.getName());
        assertThat(record.get("requestId").asText()).isEqualTo(requestId);
    }

    @Test
    void log_whenClientSendsRequestId_usesItInResponseAndLog(CapturedOutput output)
            throws Exception {
        mockMvc.perform(get("/logging-probe/hello")
                        .header(RequestIdFilter.HEADER_NAME, "client-42"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "client-42"));

        JsonNode record = findRecord(output, "Проба логирования");
        assertThat(record.get("requestId").asText()).isEqualTo("client-42");
    }

    @Test
    void errorResponse_whenExceptionThrown_carriesRequestIdInHeaderBodyAndLog(
            CapturedOutput output) throws Exception {
        mockMvc.perform(get("/logging-probe/boom")
                        .header(RequestIdFilter.HEADER_NAME, "incident-7"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "incident-7"))
                .andExpect(jsonPath("$.requestId").value("incident-7"));

        JsonNode record = findRecord(output, "Необработанная ошибка");
        assertThat(record.get("level").asText()).isEqualTo("ERROR");
        assertThat(record.get("requestId").asText()).isEqualTo("incident-7");
    }

    @Test
    void log_whenAuthorizationHeaderLogged_replacesValueWithMask(CapturedOutput output)
            throws Exception {
        mockMvc.perform(get("/logging-probe/authorization")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET_TOKEN))
                .andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain(SECRET_TOKEN);
        JsonNode record = findRecord(output, "Заголовок Authorization");
        assertThat(record.get("authorization").asText()).isEqualTo("****");
        assertThat(record.get("message").asText()).isEqualTo("Заголовок Authorization: ****");
    }

    @Test
    void log_whenSensitiveFieldsLogged_replacesValuesWithMask(CapturedOutput output)
            throws Exception {
        mockMvc.perform(get("/logging-probe/login")).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain("hunter2", "ivan@example.com");
        JsonNode record = findRecord(output, "Попытка входа");
        assertThat(record.get("password").asText()).isEqualTo("****");
        assertThat(record.get("email").asText()).isEqualTo("****");
    }

    private static JsonNode findRecord(CapturedOutput output, String messagePrefix)
            throws Exception {
        JsonNode found = null;
        for (String line : output.getAll().split("\\R")) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode record = JSON.readTree(line);
            if (record.path("message").asText().startsWith(messagePrefix)) {
                found = record;
            }
        }
        assertThat(found).as("запись лога «%s»", messagePrefix).isNotNull();
        return found;
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @RestController
        static class ProbeController {

            private static final Logger LOG = LoggerFactory.getLogger(ProbeController.class);

            @GetMapping("/logging-probe/hello")
            String hello() {
                LOG.info("Проба логирования");
                return "ok";
            }

            @GetMapping("/logging-probe/authorization")
            String authorization(@RequestHeader(HttpHeaders.AUTHORIZATION) String header) {
                LOG.info("Заголовок Authorization: {}", header, kv("authorization", header));
                return "ok";
            }

            @GetMapping("/logging-probe/login")
            String login() {
                LOG.info("Попытка входа", kv("email", "ivan@example.com"),
                        kv("password", "hunter2"));
                return "ok";
            }

            @GetMapping("/logging-probe/boom")
            String boom() {
                throw new IllegalStateException("таблица users недоступна");
            }
        }
    }
}
