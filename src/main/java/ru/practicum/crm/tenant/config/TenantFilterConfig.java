package ru.practicum.crm.tenant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantContextFilter;

@Configuration
public class TenantFilterConfig {

    @Bean
    public TenantContextFilter tenantContextFilter(TenantActiveChecker tenantActiveChecker,
                                                   ObjectMapper objectMapper,
                                                   TenantContext tenantContext) {
        return new TenantContextFilter(tenantActiveChecker, objectMapper, tenantContext);
    }
}
