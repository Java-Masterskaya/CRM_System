package ru.practicum.crm.security.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        return new FilterRegistrationBean<>(filter);
    }
}
