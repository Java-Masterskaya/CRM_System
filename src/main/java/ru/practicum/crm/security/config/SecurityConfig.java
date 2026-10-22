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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
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
            BearerTokenResolver bearerTokenResolver,
            @Value("${app.security.authorization.enabled:false}")
            boolean authorizationEnabled
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(
                                        request.getRequestURI(),
                                        response,
                                        objectMapper,
                                        ErrorCode.UNAUTHENTICATED
                                )
                        )
                )
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.POST,
                                    "/auth/register", "/auth/login", "/auth/refresh",
                                    "/auth/logout").permitAll()
                            // Право на профиль проверяет @PreAuthorize на методе сервиса
                            // (T-030); здесь маршрут только требует токен.
                            .requestMatchers("/client/profile").authenticated()
                            .requestMatchers(HttpMethod.GET, "/client/requests").authenticated()
                            .requestMatchers("/health", "/actuator/**", "/v3/api-docs/**",
                                    "/swagger-ui/**", "/swagger-ui.html").permitAll()
                            .requestMatchers("/system/**").permitAll();

                    if (authorizationEnabled) {
                        // Маршрут открывается здесь только для запросов с токеном, а нужное
                        // право проверяет @PreAuthorize на методе сервиса (T-030). Маршрут,
                        // которого нет в этом списке, по-прежнему закрыт (T-022).
                        authorize.requestMatchers("/admin/tenant/settings")
                                .authenticated()
                                .requestMatchers(HttpMethod.PUT, "/admin/requests/*/status")
                                .authenticated()
                                .requestMatchers(
                                        HttpMethod.PUT,
                                        "/admin/requests/*/assignee"
                                )
                                .hasAuthority(PermissionCode.REQUEST_ASSIGN.name())
                                .requestMatchers(HttpMethod.GET, "/admin/requests")
                                .authenticated()
                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/admin/requests/*",
                                        "/admin/requests/*/status-history",
                                        "/admin/requests/*/audit-log"
                                )
                                .authenticated()
                                .requestMatchers(HttpMethod.GET, "/admin/queue")
                                .authenticated()
                                .requestMatchers("/analytics/**")
                                .hasAuthority(PermissionCode.ANALYTICS_READ.name())
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/client/requests"
                                )
                                .hasAuthority(PermissionCode.REQUEST_CREATE.name())
                                .requestMatchers(HttpMethod.PUT, "/admin/requests/*")
                                .hasAuthority(PermissionCode.REQUEST_EDIT.name())
                                .requestMatchers(HttpMethod.POST, "/admin/requests/*/take")
                                .authenticated()
                                .requestMatchers(
                                        "/admin/**",
                                        "/client/**"
                                )
                                .denyAll()
                                .anyRequest()
                                .authenticated();
                    } else {
                        authorize.anyRequest().authenticated();
                    }
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(
                                (request, response, exception) ->
                                        writeProblem(
                                                request.getRequestURI(),
                                                response,
                                                objectMapper,
                                                ErrorCode.UNAUTHENTICATED
                                        )
                        )
                        .accessDeniedHandler(
                                (request, response, exception) ->
                                        writeProblem(
                                                request.getRequestURI(),
                                                response,
                                                objectMapper,
                                                ErrorCode.ACCESS_DENIED
                                        )
                        )
                )
                .addFilterAfter(
                        tenantContextFilter,
                        BearerTokenAuthenticationFilter.class
                )
                .addFilterBefore(
                        systemSecretFilter,
                        AuthorizationFilter.class
                );

        return http.build();
    }

    private static void writeProblem(
            String requestUri,
            HttpServletResponse response,
            ObjectMapper objectMapper,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(
                response.getOutputStream(),
                ProblemDetailFactory.create(
                        errorCode,
                        null,
                        URI.create(requestUri),
                        List.of()
                )
        );
    }

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantFilterRegistration(
            TenantContextFilter tenantContextFilter
    ) {
        FilterRegistrationBean<TenantContextFilter> registration =
                new FilterRegistrationBean<>(tenantContextFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter =
                new JwtGrantedAuthoritiesConverter();

        authoritiesConverter.setAuthoritiesClaimName("permissions");
        authoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        return converter;
    }

    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver defaultResolver =
                new DefaultBearerTokenResolver();

        return request -> {
            String path = request.getRequestURI();
            String contextPath = request.getContextPath();

            if (contextPath != null
                    && !contextPath.isEmpty()
                    && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }

            if (path.equals("/auth")
                    || path.startsWith("/auth/")
                    || path.equals("/health")) {
                return null;
            }

            return defaultResolver.resolve(request);
        };
    }
}
