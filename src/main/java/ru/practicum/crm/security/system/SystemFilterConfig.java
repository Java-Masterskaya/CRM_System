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
        FilterRegistrationBean<SystemSecretFilter> registration =
                new FilterRegistrationBean<>(filter);
        // Фильтр должен выполняться внутри Spring Security, а не как отдельный servlet-фильтр.
        registration.setEnabled(false);
        return registration;
    }
}
