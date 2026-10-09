package ru.practicum.crm.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ProblemDetailFactory;
import ru.practicum.crm.security.api.PermissionCode;
import ru.practicum.crm.security.system.SystemSecretFilter;
import ru.practicum.crm.tenant.api.TenantContextFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TenantContextFilter tenantContextFilter,
            SystemSecretFilter systemSecretFilter,
            ObjectMapper objectMapper,
            @Value("${app.security.authorization.enabled:false}") boolean authorizationEnabled
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.POST,
                                    "/auth/register", "/auth/login", "/auth/refresh").permitAll()
                            .requestMatchers("/health", "/actuator/**", "/v3/api-docs/**",
                                    "/swagger-ui/**", "/swagger-ui.html").permitAll()
                            .requestMatchers("/system/**").permitAll();

                    if (authorizationEnabled) {
                        authorize.requestMatchers(HttpMethod.GET, "/admin/tenant/settings")
                                .hasAuthority(PermissionCode.TENANT_SETTINGS_READ.name())
                                .requestMatchers(HttpMethod.PUT, "/admin/tenant/settings")
                                .hasAuthority(PermissionCode.TENANT_SETTINGS_MANAGE.name())
                                .requestMatchers("/admin/**", "/client/**").denyAll()
                                .anyRequest().authenticated();
                    } else {
                        // Не блокируем бизнес-маршруты до появления рабочего механизма входа.
                        authorize.anyRequest().permitAll();
                    }
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeProblem(
                                request.getRequestURI(), response, objectMapper,
                                ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) -> writeProblem(
                                request.getRequestURI(), response, objectMapper,
                                ErrorCode.ACCESS_DENIED)))
                .addFilterAfter(tenantContextFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(systemSecretFilter, AuthorizationFilter.class);

        return http.build();
    }

    private static void writeProblem(String requestUri,
                                     HttpServletResponse response,
                                     ObjectMapper objectMapper,
                                     ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ProblemDetailFactory.create(
                errorCode, null, URI.create(requestUri), List.of()));
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
