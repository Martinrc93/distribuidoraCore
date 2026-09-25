package com.distribuidora.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void propagatesValidatedRequestIdIntoResponseAndMdc() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "corr-2026.09:24_a1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcValue = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcValue.set(MDC.get("requestId")));

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("corr-2026.09:24_a1");
        assertThat(mdcValue.get()).isEqualTo("corr-2026.09:24_a1");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void replacesOversizedOrUnsafeRequestIds() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "bad\r\nheader".repeat(20));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
