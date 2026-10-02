package ru.practicum.crm.platform.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_whenRequested_isUnavailableFromExternalContour() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiDocs_whenControllerExists_isUnavailableFromExternalContour() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerUi_whenRequestedInDevelopment_isUnavailableFromExternalContour()
            throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isUnauthorized());
    }
}
