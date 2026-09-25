package com.urlshortener.common.web;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.UUID;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * Renders errors that Tomcat produces <em>before</em> the request reaches any servlet or filter - an invalid
 * percent-encoding, a rejected encoded slash, an oversized request line - in the service's standard error shape instead
 * of Tomcat's HTML report (design doc section 9.1: every error carries a requestId). Nothing about the cause is revealed.
 *
 * <p>These rejections stay a {@code 400}: at this layer there is no way to know the path was meant as a short code, so
 * the "unknown code -> 404" rule of E18 applies only to requests that reach Spring (documented in section 19).
 */
public class JsonErrorReportValve extends ErrorReportValve {

    public JsonErrorReportValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        String code = switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "VALIDATION_FAILED";
        };
        String requestId = UUID.randomUUID().toString();
        String message = status == 404 ? "No such short URL." : "The request could not be processed.";
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"" + requestId
                + "\",\"timestamp\":\"" + Instant.now() + "\",\"path\":" + quote(safePath(request))
                + ",\"fieldErrors\":null}";
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader(RequestIdFilter.HEADER, requestId);
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body);
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException e) {
            // Nothing more can be done for a connection that is already broken.
        }
    }

    /** The path is echoed only when it is plain ASCII, so a hostile request line cannot inject into the JSON body. */
    private static String safePath(Request request) {
        String uri = request.getRequestURI();
        return uri != null && uri.chars().allMatch(c -> c > 0x20 && c < 0x7f && c != '"' && c != '\\') ? uri : "";
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
