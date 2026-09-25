package com.urlshortener.api;

import com.urlshortener.analytics.api.UrlStats;
import com.urlshortener.analytics.stats.StatsService;
import com.urlshortener.api.generated.UrlsApi;
import com.urlshortener.common.security.ApiKeyAuthentication;
import com.urlshortener.shortener.api.CreateUrlRequest;
import com.urlshortener.shortener.api.UrlResource;
import com.urlshortener.shortener.service.CreateResult;
import com.urlshortener.shortener.service.UrlService;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the interface generated from {@code docs/openapi.yaml} (design doc section 20.6): if this controller drifts
 * from the contract, the build fails. It is deliberately thin - HTTP mapping only; status codes for errors are chosen by
 * {@code GlobalExceptionHandler}, never here.
 */
@RestController
public class UrlsController implements UrlsApi {

    private final UrlService urls;
    private final StatsService stats;

    public UrlsController(UrlService urls, StatsService stats) {
        this.urls = urls;
        this.stats = stats;
    }

    @Override
    public ResponseEntity<UrlResource> createUrl(CreateUrlRequest request) {
        CreateResult result = urls.create(currentKeyId(), request);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/urls/" + result.resource().shortCode()))
                    .body(result.resource());
        }
        // Idempotent replay (section 6.3): 200, not 201, and a header saying so.
        return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.resource());
    }

    @Override
    public ResponseEntity<UrlResource> getUrl(String shortCode) {
        return ResponseEntity.ok(urls.get(currentKeyId(), shortCode));
    }

    @Override
    public ResponseEntity<Void> deactivateUrl(String shortCode) {
        urls.deactivate(currentKeyId(), shortCode);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<UrlStats> getUrlStats(String shortCode, LocalDate from, LocalDate to) {
        return ResponseEntity.ok(stats.stats(currentKeyId(), shortCode, from, to));
    }

    private static String currentKeyId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication key) {
            return key.keyId();
        }
        // Unreachable behind the security chain; fail closed if the wiring ever changes.
        throw new com.urlshortener.common.error.UnauthorizedException("Missing or invalid API key.");
    }
}
