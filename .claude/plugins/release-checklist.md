# Release checklist (design §13)
- [ ] `./mvnw verify` + `verify-design-coverage.py` green
- [ ] migrations are expand-only; rollback noted
- [ ] design doc, verification report and `.claude/` reflect any changed guarantee (drift = release blocker, §21.9)
- [ ] canary the redirect service; auto-rollback on error/latency regression
- [ ] new API fields behind `app.features.*` flags
- [ ] prod secrets present (`ProdSecretsGuard` will refuse otherwise); Swagger/`/internal` off
- [ ] F10: ≥2 AZ, replica in another AZ (topology diagram)
- [ ] `npx newman run postman/url-shortener.postman_collection.json` green against the build's `local` instance (manual smoke check — not part of `./mvnw verify`, README L26)
- [ ] human sign-off recorded (§16, rule R2) if the redirect hot path, `SsrfGuard`/`UrlValidator`, `SecurityConfig`, a migration or resilience config changed
