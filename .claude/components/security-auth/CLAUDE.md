# security-auth

**Responsibility:** API-key auth, SSRF, rate limiting, secrets.

**Key classes / files:** `ApiKeyService`, `ApiKeyAuthFilter`, `SecurityConfig`, `SsrfGuard`, `IpClassifier`, `RateLimits`, `IpRateLimitFilter`, `ProdSecretsGuard`

**Invariants**
- auth filter only for /api/**; redirect never touches it
- filters delegate errors to the advice
- Argon2 at rest; 30 s verified-key cache
- limits are configuration

**Do NOT**
- Choose HTTP statuses in a filter
- Log keys/IPs
- Add a default secret to prod config
