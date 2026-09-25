# API contract rules — design §6, §20.6, §22, §21.4

- **A1 `docs/openapi.yaml` is the source of truth.** Regenerate interfaces (build does it); never hand-edit generated code.
- **A2 The redirect `GET /{shortCode}` is unversioned forever.**
- **A3 Non-breaking:** add optional request fields, response fields, endpoints. **Breaking (new version):** remove/rename fields, change a status meaning, auth, pagination shape or a field type.
- **A4 Deprecation:** ≥ 6 months after a successor; `Deprecation`/`Sunset` headers (RFC 8594).
- **A5 Error contract:** a new `ErrorCode` needs the enum, the spec enum, the §9.2 table and a `GlobalExceptionHandlerTest` row (`OpenApiContractTest` fails otherwise).
- **A6 CI:** the spec must validate (generator fails the build) and `SwaggerIT` must show the served file is byte-identical.
