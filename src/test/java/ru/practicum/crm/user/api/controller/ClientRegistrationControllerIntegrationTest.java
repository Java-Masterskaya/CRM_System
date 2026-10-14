package ru.practicum.crm.user.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.user.api.dto.ClientRegistrationRequest;

@AutoConfigureMockMvc
class ClientRegistrationControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void register_whenEmailAlreadyExistsIgnoringCase_returns409AndKeepsOneUser()
            throws Exception {
        UUID tenantId = UUID.randomUUID();
        String slug = "registration-http";
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                        + " VALUES (?, 'Registration HTTP tenant', ?, true, now(), now())",
                tenantId,
                slug
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, tenant_id, code, name, created_at, updated_at)"
                        + " VALUES (?, ?, 'CLIENT', 'Client', now(), now())"
                        + " ON CONFLICT (tenant_id, code) DO NOTHING",
                UUID.randomUUID(),
                tenantId
        );

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ClientRegistrationRequest(
                                slug, "Client@example.com", "StrongPassword1!", "Client"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ClientRegistrationRequest(
                                slug, "client@EXAMPLE.com", "StrongPassword1!", "Duplicate"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/email"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isEqualTo(1);
    }
}
