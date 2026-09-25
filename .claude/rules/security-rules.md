# Security rules — design §10, §21.4

- **S1 SSRF guard is mandatory** on every path that accepts a URL; resolve, reject if ANY address is private/loopback/link-local/CGNAT/ULA/embedded-IPv4; reject ambiguous numeric hosts; fail closed. Extend the review to any future outbound fetch.
- **S2 Secrets never in code or logs.** `application-prod.yml` has no defaults; `ProdSecretsGuard` refuses dev secrets.
- **S3 The redirect endpoint stays unauthenticated by design** and must never call the auth store.
- **S4 Ownership:** a foreign key gets `403` (authenticated surface), the public surface gets a uniform `404`.
- **S5 Validation is a gate,** before business logic; aliases are checked case-insensitively against the reserved list (anti-bypass).
- **S6 No internals in error bodies:** only `requestId`; detail is logged server-side.
- **S7 API keys:** opaque, Argon2-hashed, rotated by issue + revoke; the plaintext is shown once.
- **S8 `/internal/**` exists only in `local`/`test` profiles.**
