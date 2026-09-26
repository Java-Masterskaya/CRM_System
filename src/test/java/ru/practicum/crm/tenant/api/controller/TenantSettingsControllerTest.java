package ru.practicum.crm.tenant.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDto;
import ru.practicum.crm.tenant.service.TenantSettingsService;

@WebMvcTest(TenantSettingsController.class)
class TenantSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantSettingsService service;

    @Test
    void getTenantSettings_whenServiceReturnsSettings_thenReturnsDto() throws Exception {
        TenantSettingsDto dto = new TenantSettingsDto("Europe/Moscow");
        when(service.getTenantSettings()).thenReturn(dto);

        mockMvc.perform(get("/admin/tenant/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Europe/Moscow"));

        verify(service).getTenantSettings();
    }

    @Test
    void updateTenantSettings_whenRequestIsValid_thenReturnsUpdatedDto() throws Exception {
        TenantSettingsDto request = new TenantSettingsDto("Europe/Paris");
        TenantSettingsDto response = new TenantSettingsDto("Europe/Paris");
        when(service.updateTenantSettings(any(TenantSettingsDto.class))).thenReturn(response);

        mockMvc.perform(put("/admin/tenant/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Europe/Paris\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Europe/Paris"));

        verify(service).updateTenantSettings(request);
    }

    @Test
    void updateTenantSettings_whenTimezoneIsInvalid_thenReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/admin/tenant/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Not/AZone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(service, never()).updateTenantSettings(any(TenantSettingsDto.class));
    }
}
