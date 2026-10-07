package ru.practicum.crm.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.practicum.crm.common.error.ProblemDetailFactory;

final class SecurityTestRequestIdFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Request-Id";
    private static final String REQUEST_ID = "security-test-request-id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        MDC.put(ProblemDetailFactory.MDC_REQUEST_ID, REQUEST_ID);
        response.setHeader(HEADER_NAME, REQUEST_ID);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ProblemDetailFactory.MDC_REQUEST_ID);
        }
    }
}
