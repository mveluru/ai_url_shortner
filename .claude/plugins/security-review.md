# Security review checklist (pre-merge)
- [ ] every new URL input goes through `UrlValidator`/`SsrfGuard`
- [ ] no secrets/PII in code, logs, errors
- [ ] redirect path has no new dependency on auth/queue
- [ ] ownership checks (403) on every new management route
- [ ] alias/reserved/injection payloads tested
- [ ] `/internal/**` still profile-gated
- [ ] error bodies leak nothing
