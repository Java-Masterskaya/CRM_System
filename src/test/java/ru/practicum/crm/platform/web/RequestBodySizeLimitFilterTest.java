package ru.practicum.crm.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import ru.practicum.crm.common.error.PayloadTooLargeException;

class RequestBodySizeLimitFilterTest {

    private static final int LIMIT_BYTES = 10;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final RequestBodySizeLimitFilter filter =
            new RequestBodySizeLimitFilter(DataSize.ofBytes(LIMIT_BYTES), objectMapper);

    @Test
    void filter_whenDeclaredLengthExceedsLimit_rejectsWithoutCallingChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/requests");
        request.setContent(new byte[LIMIT_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("PAYLOAD_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void filter_whenBodyWithinLimit_passesFullBodyToChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/requests");
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = Objects.requireNonNull(chain.getRequest());
        assertThat(passed.getInputStream().readAllBytes()).hasSize(5);
    }

    @Test
    void filter_whenLengthUnknownAndBodyTooLarge_failsWhileReading() throws Exception {
        MockHttpServletRequest request = new UnknownLengthRequest();
        request.setContent(new byte[LIMIT_BYTES * 2]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = Objects.requireNonNull(chain.getRequest());
        assertThatThrownBy(() -> passed.getInputStream().readAllBytes())
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void filter_whenBodyReadAsText_appliesSameLimit() throws Exception {
        MockHttpServletRequest request = new UnknownLengthRequest();
        request.setContent("x".repeat(LIMIT_BYTES * 2).getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = Objects.requireNonNull(chain.getRequest());
        BufferedReader reader = passed.getReader();
        assertThatThrownBy(reader::readLine).isInstanceOf(PayloadTooLargeException.class);
    }

    private static final class UnknownLengthRequest extends MockHttpServletRequest {

        private UnknownLengthRequest() {
            super("POST", "/requests");
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }
    }
}
