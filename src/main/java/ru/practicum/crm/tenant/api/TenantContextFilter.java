package ru.practicum.crm.tenant.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantActiveChecker tenantActiveChecker;

    public TenantContextFilter(TenantActiveChecker tenantActiveChecker) {
        this.tenantActiveChecker = tenantActiveChecker;
    }

    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/api/auth",
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            Authentication authentication =
                    SecurityContextHolder.getContext().getAuthentication();

            UUID tenantId = extractTenantId(authentication);

            if (tenantId == null) {
                response.sendError(
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Tenant is not defined"
                );
                return;
            }

            if (!tenantActiveChecker.isActive(tenantId)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant is not active");
                return;
            }

            TenantContext.setTenantId(tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID extractTenantId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof UserDetails userDetails)) {
            return null;
        }

        try {
            return UUID.fromString(userDetails.getUsername());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
