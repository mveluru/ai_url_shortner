package com.urlshortener.common.web;

import com.urlshortener.common.error.ErrorCode;
import com.urlshortener.common.error.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Errors raised by the servlet container itself, before Spring MVC sees the request (a malformed percent-encoding, an
 * oversized request line, ...), are dispatched to {@code /error}. Boot's default body for those has a different shape and
 * no {@code requestId}, which would break the guarantee that every error is debuggable from the id alone (design doc
 * section 9.1). This controller renders them in the standard {@link ErrorResponse}, revealing nothing about the cause.
 */
@RestController
class ContainerErrorController implements ErrorController {

    private final Clock clock;

    ContainerErrorController(Clock clock) {
        this.clock = clock;
    }

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = raw instanceof Integer i ? i : 500;
        ErrorCode code = switch (status) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof String s ? s : UUID.randomUUID().toString();
        String message = code == ErrorCode.NOT_FOUND ? "No such short URL." : "The request could not be processed.";
        ErrorResponse body = new ErrorResponse(code, message, requestId, clock.instant(),
                original != null ? original.toString() : request.getRequestURI(), null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(RequestIdFilter.HEADER, requestId);
        return ResponseEntity.status(HttpStatus.valueOf(code.status().value())).headers(headers).body(body);
    }
}
