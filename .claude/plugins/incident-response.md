# Incident response — alert → design row
| Alert | Look at |
|---|---|
| RedirectP99LatencyHigh | F1 Redis, F4 replica, F7 stampede |
| CircuitBreakerOpen{name} | redis-cache→F1 · mysql-read→F4 · mysql-write→F3 · mq-publish→F5 · auth-lookup→F11 |
| RetryExhaustionRising | leading indicator of the above |
| ShortCodeCollision | **F9 data-integrity incident — page, do not retry** |
| CacheInvalidationBacklog | F2: deleted links may still redirect |
| ClickQueueBacklog | F6: analytics stale (see `updatedAt`) |

If a mitigation did **not** behave as documented, that is a higher-priority finding than the trigger.
