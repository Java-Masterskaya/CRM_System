package ru.practicum.crm.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.PayloadTooLargeException;
import ru.practicum.crm.common.error.ProblemDetailFactory;

@Component
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;
    private final ObjectWriter problemWriter;

    public RequestBodySizeLimitFilter(
            @Value("${app.web.max-request-body-size:1MB}") DataSize maxBodySize,
            ObjectMapper objectMapper) {
        this.maxBodyBytes = maxBodySize.toBytes();
        this.problemWriter = objectMapper.writer();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            writePayloadTooLarge(request, response);
            return;
        }
        filterChain.doFilter(new LimitedBodyRequest(request, maxBodyBytes), response);
    }

    private void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(ErrorCode.PAYLOAD_TOO_LARGE.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ProblemDetail problem = ProblemDetailFactory.create(ErrorCode.PAYLOAD_TOO_LARGE, null,
                URI.create(request.getRequestURI()), List.of());
        problemWriter.writeValue(response.getOutputStream(), problem);
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long limitBytes;
        private long readBytes;

        private LimitedBodyRequest(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream());
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        private void count(int bytes) throws PayloadTooLargeException {
            readBytes += bytes;
            if (readBytes > limitBytes) {
                throw new PayloadTooLargeException(limitBytes);
            }
        }

        private final class LimitedInputStream extends ServletInputStream {

            private final ServletInputStream delegate;

            private LimitedInputStream(ServletInputStream delegate) {
                this.delegate = delegate;
            }

            @Override
            public int read() throws IOException {
                int value = delegate.read();
                if (value != -1) {
                    count(1);
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = delegate.read(buffer, offset, length);
                if (read > 0) {
                    count(read);
                }
                return read;
            }

            @Override
            public boolean isFinished() {
                return delegate.isFinished();
            }

            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                delegate.setReadListener(readListener);
            }
        }
    }
}
