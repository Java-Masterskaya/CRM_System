package ru.practicum.crm.security.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import ru.practicum.crm.tenant.api.TenantContextFilter;

@Configuration
public class SecurityConfig {

    // TODO: После включения Security в проект,
    //  необходимо будет настроить авторизацию и аутентификацию пользователей.
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TenantContextFilter tenantContextFilter
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().permitAll())
                .addFilterAfter(tenantContextFilter, AnonymousAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantFilterRegistration(
            TenantContextFilter tenantContextFilter) {
        FilterRegistrationBean<TenantContextFilter> registration =
                new FilterRegistrationBean<>(tenantContextFilter);
        registration.setEnabled(false);
        return registration;
    }
}
