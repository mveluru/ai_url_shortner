package com.urlshortener.common.error;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.web.RequestIdFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * The single place HTTP status codes are chosen (design doc section 9.3). Domain exceptions carry their own
 * {@link ErrorCode}; framework/infrastructure exceptions are mapped by the table in section 9.3. Filters that
 * reject requests (auth, rate limit) delegate here through the {@code HandlerExceptionResolver}, so even
 * pre-controller failures share one body shape and one mapping.
 *
 * <p>Responses never contain stack traces, SQL or exception class names - only {@code requestId} (section 9.4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Request attribute read by the access-log filter. */
    public static final String ERROR_CODE_ATTRIBUTE = GlobalExceptionHandler.class.getName() + ".code";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_ECHOED_VALUE = 100;

    private final Clock clock;
    private final MeterRegistry meters;
    private final AppProperties props;

    public GlobalExceptionHandler(Clock clock, MeterRegistry meters, AppProperties props) {
        this.clock = clock;
        this.meters = meters;
        this.props = props;
    }

    // ---- Domain hierarchy -------------------------------------------------------------------------------------

    @ExceptionHandler(UrlShortenerException.class)
    public ResponseEntity<Object> handleDomain(UrlShortenerException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        switch (ex) {
            case RateLimitedException r -> headers.set(HttpHeaders.RETRY_AFTER, Long.toString(r.retryAfterSeconds()));
            case ServiceUnavailableException s -> headers.set(HttpHeaders.RETRY_AFTER, retryAfter());
            default -> { }
        }
        if (ex.errorCode().status().is5xxServerError()) {
            log.warn("Dependency failure mapped to {}: {}", ex.errorCode(), ex.getMessage(), ex);
        }
        return respond(ex.errorCode(), ex.getMessage(), null, headers, request);
    }

    // ---- Infrastructure / cross-cutting -----------------------------------------------------------------------

    /** E13's race is resolved at the DB: the loser's INSERT throws and is translated here, not pre-checked. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        if (UniqueConstraint.of(ex) == UniqueConstraint.SHORT_CODE) {
            return respond(ErrorCode.ALIAS_TAKEN, "The requested alias is already in use.", null,
                    new HttpHeaders(), request);
        }
        return unexpected(ex, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                       HttpServletRequest request) {
        return respond(ErrorCode.RESOURCE_MODIFIED,
                "The resource was modified concurrently; re-fetch and retry.", null, new HttpHeaders(), request);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Object> handleCircuitOpen(CallNotPermittedException ex, HttpServletRequest request) {
        log.warn("Circuit breaker open: {}", ex.getCausingCircuitBreakerName());
        return respond(ErrorCode.SERVICE_UNAVAILABLE, "A required dependency is temporarily unavailable.", null,
                retryAfterHeaders(), request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<Object> handleDependencyDown(Exception ex, HttpServletRequest request) {
        log.warn("Database unavailable", ex);
        return respond(ErrorCode.SERVICE_UNAVAILABLE, "A required dependency is temporarily unavailable.", null,
                retryAfterHeaders(), request);
    }

    @ExceptionHandler({TimeoutException.class, QueryTimeoutException.class})
    public ResponseEntity<Object> handleTimeout(Exception ex, HttpServletRequest request) {
        log.warn("Dependency timeout", ex);
        return respond(ErrorCode.UPSTREAM_TIMEOUT, "A dependency did not respond in time.", null,
                retryAfterHeaders(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex,
                                                            HttpServletRequest request) {
        List<FieldError> errors = ex.getConstraintViolations().stream().map(this::toFieldError).toList();
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed.", errors, new HttpHeaders(), request);
    }

    /**
     * E18: a path containing characters outside the code charset (encoded slashes, semicolons, NUL, backslashes...) is
     * refused by Spring Security's firewall before routing. To a caller that is simply "no such code" - a 404 that does not
     * reveal which rule fired - not a distinct validation error and not the container's default page.
     */
    @ExceptionHandler(RequestRejectedException.class)
    public ResponseEntity<Object> handleRejectedRequest(RequestRejectedException ex, HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, "No such short URL.", null, new HttpHeaders(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(ErrorCode.UNAUTHORIZED, "Missing or invalid API key.", null, new HttpHeaders(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(ErrorCode.FORBIDDEN, "Access to this resource is forbidden.", null, new HttpHeaders(), request);
    }

    /** Catch-all: full detail is logged server-side under the requestId; the body carries no internals. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        return unexpected(ex, request);
    }

    private ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred. Quote the requestId when reporting it.",
                null, new HttpHeaders(), request);
    }

    // ---- Spring MVC exceptions (ResponseEntityExceptionHandler hooks) ------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        BindingResult binding = ex.getBindingResult();
        List<FieldError> errors = binding.getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), echo(fe.getRejectedValue()), fe.getCode(),
                        fe.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed.", errors, headers, servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed.", List.of(), headers, servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.MALFORMED_JSON, "The request body is not valid JSON or has a field of the wrong type.",
                null, headers, servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json.", null, headers,
                servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.NOT_ACCEPTABLE, "This API only produces application/json.", null, headers,
                servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, "This HTTP method is not supported on this path.", null, headers,
                servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.NO_HANDLER, "No route matches this request path.", null, headers, servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.NO_HANDLER, "No route matches this request path.", null, headers, servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new FieldError(ex.getParameterName(), null, "Required", "is required")), headers,
                servlet(request));
    }

    /** Malformed {@code from}/{@code to} dates are an INVALID_RANGE (section 9.2); other mismatches are validation errors. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        if ("from".equals(name) || "to".equals(name)) {
            return respond(ErrorCode.INVALID_RANGE, "'" + name + "' must be an ISO-8601 date (yyyy-MM-dd).", null,
                    headers, servlet(request));
        }
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new FieldError(String.valueOf(name), echo(ex.getValue()), "TypeMismatch",
                        "has the wrong type")), headers, servlet(request));
    }

    /**
     * Safety net: any other Spring MVC exception still gets the one error shape instead of Spring's default
     * ProblemDetail body, so "every error response is debuggable via requestId" (section 9.1) always holds.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled MVC exception", ex);
        }
        return respond(code, statusCode.is5xxServerError() ? "An unexpected error occurred." : "Bad request.", null,
                headers, servlet(request));
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------

    private ResponseEntity<Object> respond(ErrorCode code, String message, List<FieldError> fieldErrors,
                                           HttpHeaders extraHeaders, HttpServletRequest request) {
        request.setAttribute(ERROR_CODE_ATTRIBUTE, code.name());
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        meters.counter("http_errors", "code", code.name(), "endpoint", pattern != null ? pattern.toString() : "unmatched")
                .increment();

        ErrorResponse body = new ErrorResponse(code, message, RequestIdFilter.current(request), clock.instant(),
                request.getRequestURI(), fieldErrors);
        HttpHeaders headers = new HttpHeaders();
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        // Explicit content type: the redirect route produces */*, so never rely on content negotiation for errors.
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(code.status()).headers(headers).body(body);
    }

    private ResponseEntity<Object> respond(ErrorCode code, String message, List<FieldError> fieldErrors,
                                           HttpHeaders extraHeaders, ServletWebRequest request) {
        return respond(code, message, fieldErrors, extraHeaders, request.getRequest());
    }

    private static ServletWebRequest servlet(WebRequest request) {
        return (ServletWebRequest) request;
    }

    private FieldError toFieldError(ConstraintViolation<?> v) {
        return new FieldError(v.getPropertyPath().toString(), echo(v.getInvalidValue()),
                v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(), v.getMessage());
    }

    private static Object echo(Object value) {
        if (value instanceof CharSequence s && s.length() > MAX_ECHOED_VALUE) {
            return s.subSequence(0, MAX_ECHOED_VALUE) + "...";
        }
        return value;
    }

    private String retryAfter() {
        return Integer.toString(props.http().retryAfterSeconds());
    }

    private HttpHeaders retryAfterHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, retryAfter());
        return h;
    }
}
