package com.urlshortener.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates {@code X-API-Key} for the management API only. The redirect endpoint is never routed through here
 * (section 8.1/10.1: no dependency on the auth service). Failures are handed to the {@code HandlerExceptionResolver}
 * so the single {@code GlobalExceptionHandler} produces the response - a filter must not choose HTTP statuses itself.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String MANAGEMENT_PREFIX = "/api/";

    private final ApiKeyService keys;
    private final HandlerExceptionResolver exceptions;

    public ApiKeyAuthFilter(ApiKeyService keys, HandlerExceptionResolver exceptions) {
        this.keys = keys;
        this.exceptions = exceptions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(MANAGEMENT_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String keyId = keys.authenticate(request.getHeader(HEADER));
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(keyId));
        } catch (RuntimeException e) {
            SecurityContextHolder.clearContext();
            exceptions.resolveException(request, response, null, e);
            return;
        }
        chain.doFilter(request, response);
    }
}
