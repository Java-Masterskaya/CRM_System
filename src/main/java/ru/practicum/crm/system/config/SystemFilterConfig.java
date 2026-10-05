package ru.practicum.crm.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.practicum.crm.system.api.SystemSecretFilter;

@Configuration
public class SystemFilterConfig {

    @Bean
    public SystemSecretFilter systemSecretFilter(SystemSecretProperties properties,
                                                 ObjectMapper objectMapper) {
        return new SystemSecretFilter(properties, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<SystemSecretFilter> systemSecretFilterRegistration(
            SystemSecretFilter filter
    ) {
        FilterRegistrationBean<SystemSecretFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
