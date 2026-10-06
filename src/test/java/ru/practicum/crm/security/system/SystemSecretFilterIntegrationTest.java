package ru.practicum.crm.security.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.base.BaseIntegrationTest;

@AutoConfigureMockMvc
@Import(SystemSecretFilterIntegrationTest.TestController.class)
class SystemSecretFilterIntegrationTest extends BaseIntegrationTest {

    private static final String VALID_SECRET = "test-system-secret";

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SystemSecretProperties properties;

    @Autowired
    private TestController testController;

    @AfterEach
    void tearDown() {
        testController.called = false;
    }

    @Test
    void request_whenSecretIsMissing_returnsUnauthorized()
            throws Exception {

        mockMvc.perform(get("/system/test"))
                .andExpect(status().isUnauthorized());

        assertThat(testController.called).isFalse();
    }

    @Test
    void request_whenSecretIsInvalid_returnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get("/system/test")
                                .header("X-System-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());

        assertThat(testController.called).isFalse();
    }

    @Test
    void request_whenSecretIsValid_passesToController()
            throws Exception {

        mockMvc.perform(
                        get("/system/test")
                                .header("X-System-Secret", properties.secret()))
                .andExpect(status().isOk());

        assertThat(testController.called).isTrue();
    }

    @Test
    void request_whenPathIsNotSystem_doesNotRequireSystemSecret()
            throws Exception {

        mockMvc.perform(get("/not-system/test"))
                .andExpect(status().isOk());

        assertThat(testController.called).isTrue();
    }

    @RestController
    static class TestController {

        private boolean called;

        @GetMapping("/system/test")
        void systemEndpoint() {
            called = true;
        }

        @GetMapping("/not-system/test")
        void regularEndpoint() {
            called = true;
        }
    }
}
