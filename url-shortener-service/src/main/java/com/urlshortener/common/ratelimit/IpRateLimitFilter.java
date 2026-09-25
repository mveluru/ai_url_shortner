package com.urlshortener.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Per-IP limits ahead of authentication (design doc section 10.4): the coarse pre-auth limit on {@code /api/**} that
 * protects validation logic from unauthenticated flooding, and the much higher cap on the public redirect endpoint
 * that blunts basic denial-of-service without hurting legitimate traffic.
 *
 * <p>The client IP is {@code getRemoteAddr()}; behind the gateway set {@code server.forward-headers-strategy=native} so
 * it reflects the real client. Only IP <em>equality</em> is used here; raw IPs are never logged or stored.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RateLimits limits;
    private final HandlerExceptionResolver exceptions;

    public IpRateLimitFilter(RateLimits limits, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptions) {
        this.limits = limits;
        this.exceptions = exceptions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(isManagement(request) || isRedirect(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (isManagement(request)) {
                limits.checkApi(request.getRemoteAddr());
            } else {
                limits.checkRedirect(request.getRemoteAddr());
            }
        } catch (RuntimeException e) {
            exceptions.resolveException(request, response, null, e);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isManagement(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    /** {@code GET /{shortCode}}: exactly one path segment, and not one of the operational routes. */
    private static boolean isRedirect(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "GET".equals(request.getMethod()) && uri.length() > 1 && uri.indexOf('/', 1) < 0
                && !uri.equals("/swagger-ui.html") && !uri.equals("/favicon.ico");
    }
}
