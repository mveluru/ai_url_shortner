# Engineering rules (enforced, not advisory) — design §16, §17, §21.4

- **R1 Quality gates before "done":** `./mvnw verify` (unit + IT + failure-injection) and `scripts/verify-design-coverage.py` pass. No skipped tests to get green.
- **R2 Human sign-off required for:** redirect hot path (`UrlLookupService`, `RedisUrlCache`, `RedirectController`), `SsrfGuard`/`UrlValidator`, `SecurityConfig`, any Flyway migration, resilience config.
- **R3 Traceability:** tag `[generated]/[edited]/[rejected]`, e.g. `[edited] alias regex — tightened to disallow leading hyphen`.
- **R4 Never silently narrow a documented guarantee.** If a guarantee must weaken, update the design doc and the verification report in the same change.
- **R5 "Tested" means the right kind:** a failure mode F1–F13 is covered only by a failure-injection test against real infrastructure. A mocked unit test does not count. Tag with `@Covers`.
- **R6 A test must be able to fail.** No assertions on values the test itself defines; verify wiring before injecting faults (see `AbstractFaultInjectionIT`).
- **R7 Out of scope (design §1/§19):** multi-tenant OAuth, multi-region writes, bulk import, landing pages, malware scanning. Don't add them silently.
