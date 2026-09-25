# data-layer

**Responsibility:** MySQL schema, Flyway, id allocation, optional read replica.

**Key classes / files:** `V1__urls.sql`, `V2__api_keys.sql`, `V3__analytics.sql`, `UrlEntity`, `IdBlockAllocator`, `ReplicaRoutingConfig`

**Invariants**
- every short_code column is utf8mb4_bin
- Flyway owns schema (ddl-auto=validate)
- UTC via connectionTimeZone + hibernate.jdbc.time_zone
- read-only tx → replica; auth/metadata/writes → primary

**Do NOT**
- Edit an applied migration
- Rely on repository default readOnly for auth/ownership reads
- Use TIMESTAMP
