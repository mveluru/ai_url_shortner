# Architecture diagrams (Mermaid)

## 1. Components

```mermaid
flowchart LR
  C[Client / Browser] --> GW[Gateway / LB<br/>TLS, per-IP limit]
  GW -->|GET /code| R[Redirect service<br/>highest availability]
  GW -->|/api/v1/*| S[Shortener service]
  GW -->|/api/v1/*/stats| A[Analytics service]
  R --> RC[(Redis cache-aside)]
  S --> RC
  R -->|fallback| MR[(MySQL read replica)]
  S --> MP[(MySQL primary<br/>urls, url_id_seq, api_keys)]
  R -. click event, fire-and-forget .-> Q{{RabbitMQ<br/>quorum queue + DLQ}}
  Q --> A
  A --> AG[(click_aggregates<br/>processed_events)]
  MP -. replication .-> MR
  style R fill:#d4f4dd,stroke:#2d8a4e
```

## 2. Create (idempotency and the alias race)

```mermaid
sequenceDiagram
  participant K as Client
  participant S as Shortener
  participant D as MySQL
  participant C as Redis
  K->>S: POST /api/v1/urls (X-API-Key)
  S->>S: rate limit, validate URL / alias / expiry (SSRF)
  S->>D: find fingerprint (owner, kind, url)
  alt live claim within 24h
    S-->>K: 200 Idempotent-Replay: true (original resource)
  else none
    S->>S: id from pre-allocated block, code = Base62(Feistel(id))
    S->>D: INSERT (UNIQUE short_code, UNIQUE fingerprint)
    alt short_code violation, custom alias
      S-->>K: 409 ALIAS_TAKEN
    else short_code violation, auto code
      Note over S: clashes with a custom alias: next id. Clashes with an auto code: F9, 500 + alert
    else fingerprint violation (race)
      S-->>K: 200 replay of the winner
    else ok
      S->>C: write-through (best effort)
      S-->>K: 201 + Location
    end
  end
```

## 3. Redirect (hit, miss, stampede, Redis down)

```mermaid
sequenceDiagram
  participant B as Browser
  participant R as Redirect
  participant C as Redis
  participant D as MySQL (replica)
  participant Q as RabbitMQ
  B->>R: GET /code
  R->>C: get (breaker redis-cache, 1 jittered retry)
  alt Hit
    R->>R: check active + expiry NOW
  else Miss
    R->>C: SET lock NX PX
    alt lock won
      R->>D: read (breaker mysql-read, 2 retries)
      R->>C: populate
    else lock lost
      R->>C: poll ≤ lockWait, else stale copy, else read D
    end
  else Redis down / breaker OPEN
    R->>D: read directly (no repopulate)
  end
  R-)Q: click event (async, dropped on failure)
  R-->>B: 302 Location, Cache-Control: no-store  (or 404 NOT_FOUND)
```

## 4. Async analytics

```mermaid
sequenceDiagram
  participant Q as Queue (quorum)
  participant N as Consumer
  participant D as MySQL
  Q->>N: ClickEvent (at-least-once)
  N->>D: BEGIN; INSERT IGNORE processed_events(event_id)
  alt already seen (E21)
    N->>Q: ack (skip)
  else new
    N->>D: upsert row, SELECT … FOR UPDATE, bump counts/referrers/devices; COMMIT
    N->>Q: ack after commit
  end
  Note over Q,N: failure → nack/requeue → broker redelivers → after delivery-limit → DLQ
```

## 5. Data model

```mermaid
erDiagram
  urls { bigint id PK
         varchar short_code UK "utf8mb4_bin"
         text long_url
         varchar owner_key_id
         varchar idempotency_fingerprint UK "nullable"
         datetime expires_at
         bool is_active
         int version }
  api_keys { varchar key_id PK
             varchar key_hash "Argon2"
             datetime revoked_at }
  click_aggregates { varchar short_code PK "utf8mb4_bin"
                     date date PK
                     bigint click_count
                     json top_referrers
                     json device_breakdown }
  processed_events { char event_id PK }
  analytics_state { tinyint id PK
                    datetime last_aggregation_at }
  urls ||--o{ click_aggregates : "short_code (logical)"
  api_keys ||--o{ urls : "owner_key_id (logical)"
```

## 6. Failure-mode map (owner → rows)

```mermaid
flowchart TB
  subgraph Redirect
    F1[F1 Redis down] --- F2[F2 cache/DB desync] --- F7[F7 stampede] --- F12[F12 expiry sweep lag] --- F13[F13 clock skew]
  end
  subgraph Shortener
    F3[F3 primary down] --- F8[F8 counter contention] --- F9[F9 code collision]
  end
  subgraph Analytics
    F5[F5 queue down] --- F6[F6 consumer behind]
  end
  subgraph Cross-cutting
    F4[F4 read path, replica] --- F10[F10 AZ loss] --- F11[F11 auth store down]
  end
```

## 7. Deployment topology (F10, §13, §14)

```mermaid
flowchart TB
  LB[Load balancer, ≥2 AZ] --> A1[App pods AZ-a] & A2[App pods AZ-b]
  A1 & A2 --> RCL[(Redis cluster + replicas)]
  A1 & A2 --> P[(MySQL primary AZ-a)]
  A1 & A2 -->|read-only| RP[(MySQL replica AZ-b)]
  P -- binlog / GTID --> RP
  RP -. promotion on AZ loss, RPO < 1 min .-> P
  A1 & A2 --> MQ[(RabbitMQ quorum queue, 3 nodes)]
  P -. cross-region backups, RPO < 15 min .-> BK[(Backups)]
```

## 8. API versioning timeline (§6.1)

```mermaid
gantt
  dateFormat YYYY-MM
  section Versions
  v1 supported         :2026-09, 2027-12
  v2 ships (example)   :milestone, 2027-06, 0d
  v1 deprecated (Sunset header) :2027-06, 2027-12
  v1 sunset            :milestone, 2027-12, 0d
```
