# redirect-service

**Responsibility:** Read path: cache-aside, stampede guard, breaker fallback, 302.

**Key classes / files:** `UrlLookupService`, `RedisUrlCache`, `BestEffortCache`, `CacheLookupResult`, `RedirectController`, `RedirectMetrics`

**Invariants**
- hit trusts cache but re-checks active+expiry
- miss → SET NX PX lock; losers poll, then stale, then DB (bounded)
- breaker open/Redis error → DB directly, never repopulate
- no synchronized (virtual threads)
- click publish is fire-and-forget

**Do NOT**
- Wait on the queue or auth
- Update (instead of delete) cache on mutation
- Trust TTL for expiry
- Add synchronized to the lock path
