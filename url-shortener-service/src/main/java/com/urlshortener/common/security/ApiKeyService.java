package com.urlshortener.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.urlshortener.common.error.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-key lifecycle (design doc section 10.1). Keys are opaque, hashed at rest with Argon2 (a deliberately slow
 * hash), and rotated by issuing a new key and revoking the old one - a key's value never changes in place.
 *
 * <p>Format: {@code usk_<keyId>.<secret>}. The key id is the public lookup handle; the secret is 256 random bits and
 * is only ever compared against the hash.
 *
 * <p>Argon2 is intentionally expensive, so a successfully verified key is remembered briefly (keyed by a SHA-256 of
 * the presented key, never the key itself). The trade-off is that a revocation can take up to {@link #VERIFIED_TTL}
 * to take effect on an instance that has already seen the key.
 */
@Service
public class ApiKeyService {

    public static final String PREFIX = "usk_";
    static final Duration VERIFIED_TTL = Duration.ofSeconds(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;
    private final ApiKeyLookup lookup;
    private final Clock clock;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final Cache<String, String> verified = Caffeine.newBuilder()
            .expireAfterWrite(VERIFIED_TTL).maximumSize(10_000).build();

    public ApiKeyService(ApiKeyRepository repository, ApiKeyLookup lookup, Clock clock) {
        this.repository = repository;
        this.lookup = lookup;
        this.clock = clock;
    }

    /** @return the plaintext key, shown to the caller exactly once */
    @Transactional
    public IssuedKey issue(String name) {
        String keyId = HexFormat.of().formatHex(randomBytes(12));
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        repository.save(new ApiKeyEntity(keyId, encoder.encode(secret), name));
        return new IssuedKey(keyId, PREFIX + keyId + "." + secret);
    }

    /** @return true if a key existed and is now revoked */
    @Transactional
    public boolean revoke(String keyId) {
        return repository.findById(keyId).map(k -> {
            k.revoke(clock.instant());
            verified.asMap().values().removeIf(keyId::equals);
            return true;
        }).orElse(false);
    }

    /**
     * @return the owning key id
     * @throws UnauthorizedException for a missing, malformed, unknown, revoked or wrong key (one uniform error, so a
     *         caller cannot tell which)
     */
    public String authenticate(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith(PREFIX)) {
            throw new UnauthorizedException("Missing or invalid API key.");
        }
        String fingerprint = sha256Hex(presentedKey);
        String cached = verified.getIfPresent(fingerprint);
        if (cached != null) {
            return cached;
        }
        int dot = presentedKey.indexOf('.');
        if (dot < 0) {
            throw new UnauthorizedException("Missing or invalid API key.");
        }
        String keyId = presentedKey.substring(PREFIX.length(), dot);
        String secret = presentedKey.substring(dot + 1);
        ApiKeyEntity key = lookup.find(keyId).orElseThrow(() -> new UnauthorizedException("Missing or invalid API key."));
        if (key.isRevoked() || !encoder.matches(secret, key.getKeyHash())) {
            throw new UnauthorizedException("Missing or invalid API key.");
        }
        verified.put(fingerprint, keyId);
        return keyId;
    }

    public record IssuedKey(String keyId, String apiKey) {}

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
