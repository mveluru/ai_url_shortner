package com.urlshortener.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Makes springdoc's annotation-generated OpenAPI document look like it does not exist (design doc section 22.1).
 *
 * <p>springdoc cannot generate nothing without also disabling the Swagger UI, so its generated spec is relocated and then
 * hidden here: the checked-in {@code docs/openapi.yaml} must be the ONLY contract anyone can fetch. The answer is a
 * {@code 404 NO_HANDLER} through the normal error path, not a {@code 401/403}, because no credential would ever help - the
 * route is simply not part of this service's surface. The UI's own {@code swagger-config} sub-path is not affected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
class HiddenRoutesFilter extends OncePerRequestFilter {

    private static final Set<String> HIDDEN = Set.of("/v3/generated-api-docs", "/v3/generated-api-docs.yaml");

    private final HandlerExceptionResolver exceptions;

    HiddenRoutesFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptions) {
        this.exceptions = exceptions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HIDDEN.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        exceptions.resolveException(request, response, null,
                new NoHandlerFoundException(request.getMethod(), request.getRequestURI(), HttpHeaders.EMPTY));
    }
}
