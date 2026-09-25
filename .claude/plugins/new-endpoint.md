# New endpoint
1. Edit `docs/openapi.yaml` first (security scheme, every error response).
2. `./mvnw generate-sources`; implement the generated interface in `api/` (thin: HTTP mapping only).
3. Validation in a domain validator → existing `ErrorCode`; ownership → 403.
4. Data: migration if needed (expand-only) → data-layer playbook.
5. Observability: metric + access-log field; alert if actionable.
6. Tests: happy, each error row, auth/ownership, `@Covers`; run `OpenApiContractTest`.
7. Update design doc + verification report if a guarantee changed.
