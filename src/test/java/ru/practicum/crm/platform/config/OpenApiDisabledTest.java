package ru.practicum.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "springdoc.swagger-ui.enabled=false"
})
@AutoConfigureMockMvc
class OpenApiDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_whenDocumentationDisabled_isNotAvailable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
    }

    @Test
    void prodProfile_whenConfigurationLoaded_disablesDocumentation() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        PropertySource<?> prodDocument = documents.stream()
                .filter(document -> "prod".equals(
                        String.valueOf(document.getProperty("spring.config.activate.on-profile"))))
                .findFirst()
                .orElseThrow();

        assertThat(String.valueOf(prodDocument.getProperty("springdoc.api-docs.enabled")))
                .isEqualTo("false");
        assertThat(String.valueOf(prodDocument.getProperty("springdoc.swagger-ui.enabled")))
                .isEqualTo("false");
    }
}
