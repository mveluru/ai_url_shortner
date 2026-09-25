package com.urlshortener.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the {@code requestId} for the lifetime of a request (design doc sections 9.4, 12.3, 20.5).
 *
 * <p>Precedence: a well-formed {@code X-Request-Id} from the gateway, else the current trace id (so
 * requestId == trace id), else a fresh UUID. It is placed in the MDC so every log line - including ones emitted
 * before an exception is thrown - carries it, echoed as a response header, and exposed as a request attribute for
 * the exception handler.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final ObjectProvider<Tracer> tracer;

    public RequestIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request);
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolve(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        Tracer t = tracer.getIfAvailable();
        if (t != null) {
            Span span = t.currentSpan();
            if (span != null) {
                return span.context().traceId();
            }
        }
        return UUID.randomUUID().toString();
    }

    /** The current request's id, for code outside the filter chain. */
    public static String current(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTRIBUTE);
        if (attr instanceof String s) {
            return s;
        }
        String mdc = MDC.get(MDC_KEY);
        return mdc != null ? mdc : UUID.randomUUID().toString();
    }
}
