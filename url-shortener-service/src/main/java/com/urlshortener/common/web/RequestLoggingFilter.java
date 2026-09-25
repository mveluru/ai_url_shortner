package com.urlshortener.common.web;

import com.urlshortener.common.error.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One access-log line per request (design doc section 12.2): {@code requestId} (via MDC), method, path, status, latency
 * and, for errors, the error {@code code}. Deliberately absent: the query string, the client IP, headers (API key,
 * Referer) and any long URL - these can carry PII or secrets.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object code = request.getAttribute(GlobalExceptionHandler.ERROR_CODE_ATTRIBUTE);
            log.info("{} {} -> {} in {}ms{}", request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000, code != null ? " code=" + code : "");
        }
    }
}
