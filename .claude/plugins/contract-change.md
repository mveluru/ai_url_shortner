# Contract change
Classify with api-contract-rules A3. Non-breaking → edit spec + DTO record together (`OpenApiContractTest` enforces). Breaking → new `/api/v2`, keep v1 ≥ 6 months, `Deprecation`/`Sunset` headers, springdoc *groups* (§22.8). Never version the redirect.
