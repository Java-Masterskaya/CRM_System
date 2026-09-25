package ru.practicum.crm.tenant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContextFilter;

@Configuration
public class TenantFilterConfig {

    @Bean
    public TenantContextFilter tenantContextFilter(TenantActiveChecker tenantActiveChecker) {
        return new TenantContextFilter(tenantActiveChecker);
    }
}
