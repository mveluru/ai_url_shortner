package com.urlshortener.api;

import com.urlshortener.analytics.publish.ClickPublisher;
import com.urlshortener.api.generated.RedirectApi;
import com.urlshortener.redirect.Resolution;
import com.urlshortener.redirect.UrlLookupService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /{shortCode}} - public, unauthenticated and permanently unversioned (design doc section 6.1, 6.4).
 *
 * <p>{@code 302}, not {@code 301}, and {@code Cache-Control: no-store}: a cached permanent redirect would make expiry,
 * deactivation and analytics unreliable once a browser had cached it. Spring does not send {@code no-store} by default,
 * so it is set explicitly (section 20.4).
 */
@RestController
public class RedirectController implements RedirectApi {

    private final UrlLookupService lookup;
    private final ClickPublisher clicks;
    private final HttpServletRequest request;

    public RedirectController(UrlLookupService lookup, ClickPublisher clicks, HttpServletRequest request) {
        this.lookup = lookup;
        this.clicks = clicks;
        this.request = request;              // a request-scoped proxy: the generated interface has no request parameter
    }

    @Override
    public ResponseEntity<Void> redirect(String shortCode) {
        Resolution resolution = lookup.resolve(shortCode);          // throws NOT_FOUND for unknown/expired/deactivated
        clicks.publish(shortCode, request);                         // fire-and-forget: never blocks, never throws (F5)
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, resolution.longUrl())
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
