package com.urlshortener.internal;

import com.urlshortener.common.security.ApiKeyService;
import com.urlshortener.redirect.cache.BestEffortCache;
import com.urlshortener.shortener.service.UrlWriter;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only conveniences (design doc section 18, step 3; E8). Present ONLY under the {@code local} and {@code test}
 * profiles - in any other profile these beans do not exist, so the routes return NO_HANDLER, not 401. Intentionally not
 * part of {@code openapi.yaml}: they are not a product API.
 */
@RestController
@RequestMapping("/internal")
@Profile({"local", "test"})
class InternalController {

    private final ApiKeyService keys;
    private final UrlWriter writer;
    private final BestEffortCache cache;

    InternalController(ApiKeyService keys, UrlWriter writer, BestEffortCache cache) {
        this.keys = keys;
        this.writer = writer;
        this.cache = cache;
    }

    /** Issues an API key. The plaintext is returned exactly once; only its Argon2 hash is stored. */
    @PostMapping("/api-keys")
    ResponseEntity<Map<String, String>> issueKey(@RequestBody(required = false) Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        ApiKeyService.IssuedKey key = keys.issue(name);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("keyId", key.keyId(), "apiKey", key.apiKey()));
    }

    /** Key rotation: issue a new key, then revoke the old one. */
    @DeleteMapping("/api-keys/{keyId}")
    ResponseEntity<Void> revokeKey(@PathVariable String keyId) {
        return keys.revoke(keyId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** The "human/admin path" that frees a soft-deleted alias (E8): a hard delete, plus cache invalidation. */
    @DeleteMapping("/urls/{shortCode}")
    ResponseEntity<Void> hardDelete(@PathVariable String shortCode) {
        boolean deleted = writer.hardDelete(shortCode);
        cache.invalidateQuietly(shortCode);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
