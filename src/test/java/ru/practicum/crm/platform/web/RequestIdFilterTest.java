package ru.practicum.crm.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.practicum.crm.common.error.ProblemDetailFactory;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void filter_whenHeaderAbsent_generatesIdAndReturnsItInResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/requests"), response,
                new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isNotBlank();
    }

    @Test
    void filter_whenClientSendsHeader_usesClientValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/requests");
        request.addHeader(RequestIdFilter.HEADER_NAME, "client-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("client-42");
    }

    @Test
    void filter_whenClientHeaderHasUnsafeCharacters_generatesOwnId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/requests");
        request.addHeader(RequestIdFilter.HEADER_NAME, "evil\r\nSet-Cookie: x=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
                .isNotBlank()
                .doesNotContain("evil");
    }

    @Test
    void filter_whenTwoRequests_idsDiffer() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/requests"), first,
                new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/requests"), second,
                new MockFilterChain());

        assertThat(first.getHeader(RequestIdFilter.HEADER_NAME))
                .isNotEqualTo(second.getHeader(RequestIdFilter.HEADER_NAME));
    }

    @Test
    void filter_whenChainRuns_idIsInMdcAndRemovedAfterwards() throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (request, response) ->
                seenInChain.set(MDC.get(ProblemDetailFactory.MDC_REQUEST_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/requests"), response, chain);

        assertThat(seenInChain.get()).isEqualTo(response.getHeader(RequestIdFilter.HEADER_NAME));
        assertThat(MDC.get(ProblemDetailFactory.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void filter_whenChainThrows_stillRemovesIdFromMdc() {
        FilterChain chain = (request, response) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/requests"),
                new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(ProblemDetailFactory.MDC_REQUEST_ID)).isNull();
    }
}
