package ru.practicum.crm.security.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ProblemDetailFactory;

public class SystemSecretFilter extends OncePerRequestFilter {

    private static final String SECRET_HEADER = "X-System-Secret";

    private final SystemSecretProperties properties;
    private final ObjectWriter problemWriter;

    public SystemSecretFilter(SystemSecretProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.problemWriter = objectMapper.writer();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        return !path.startsWith("/system");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain
    ) throws ServletException, IOException {

        String providedSecret = request.getHeader(SECRET_HEADER);

        if (providedSecret == null || !constantTimeEquals(properties.secret(), providedSecret)) {
            writeUnauthorized(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {

        response.setStatus(ErrorCode.UNAUTHENTICATED.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ProblemDetail problem = ProblemDetailFactory.create(ErrorCode.UNAUTHENTICATED,
                "требуется корректный секрет служебного контура",
                URI.create(request.getRequestURI()), List.of());

        problemWriter.writeValue(response.getOutputStream(), problem);
    }
}
