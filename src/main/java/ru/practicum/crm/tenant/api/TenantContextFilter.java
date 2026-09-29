package ru.practicum.crm.tenant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ProblemDetailFactory;

public class TenantContextFilter extends OncePerRequestFilter {

    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/client",
            "/admin",
            "/test"
    );

    private final TenantActiveChecker tenantActiveChecker;
    private final ObjectWriter problemWriter;

    public TenantContextFilter(TenantActiveChecker tenantActiveChecker, ObjectMapper objectMapper) {
        this.tenantActiveChecker = tenantActiveChecker;
        this.problemWriter = objectMapper.writer();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PROTECTED_PREFIXES.stream().noneMatch(path::startsWith);
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
                filterChain.doFilter(request, response);
                return;
            }

            if (!tenantActiveChecker.isActive(tenantId)) {
                writeUnauthorized(request, response, "Арендатор не активен");
                return;
            }

            TenantContext.setTenantId(tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void writeUnauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            String detail
    ) throws IOException {
        response.setStatus(ErrorCode.UNAUTHENTICATED.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.UNAUTHENTICATED, detail,
                URI.create(request.getRequestURI()), List.of());
        problemWriter.writeValue(response.getOutputStream(), problem);
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
