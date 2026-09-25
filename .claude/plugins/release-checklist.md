# Release checklist (design §13)
- [ ] `./mvnw verify` + `verify-design-coverage.py` green
- [ ] migrations are expand-only; rollback noted
- [ ] design doc, verification report and `.claude/` reflect any changed guarantee (drift = release blocker, §21.9)
- [ ] canary the redirect service; auto-rollback on error/latency regression
- [ ] new API fields behind `app.features.*` flags
- [ ] prod secrets present (`ProdSecretsGuard` will refuse otherwise); Swagger/`/internal` off
- [ ] F10: ≥2 AZ, replica in another AZ (topology diagram)
