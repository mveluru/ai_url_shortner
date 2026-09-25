# shortener-service

**Responsibility:** Write path: validate → idempotency → allocate → insert → write-through.

**Key classes / files:** `UrlService`, `UrlWriter`, `UrlReader`, `IdempotencyKey`, `ClaimRules`, `UrlValidator`, `SsrfGuard`, `AliasValidator`, `ExpiryValidator`, `ShortCodeGenerator`, `IdBlockAllocator`

**Invariants**
- E1–E13 enforced here
- fingerprint = (owner, kind, url); unique nullable; released on deactivate/expiry
- custom-alias clash of an AUTO code → next id, auto-vs-auto → F9
- ids allocated OUTSIDE transactions

**Do NOT**
- Pre-check alias existence (races, E13)
- Retry INSERT/DELETE (§8.2.3)
- Return 410 for expired
- Log longUrl
