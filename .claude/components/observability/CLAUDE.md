# observability

**Responsibility:** Metrics, logs, tracing, alerts.

**Key classes / files:** `RedirectMetrics`, `ResilienceConfig`, `RequestIdFilter`, `RequestLoggingFilter`, `logback-spring.xml`, `ops/prometheus-alerts.yml`

**Invariants**
- requestId == trace id, in MDC, echoed as X-Request-Id
- metric names exactly as design §12.1
- alerts reference only exported metrics (ObservabilityIT)
- readiness = db only

**Do NOT**
- Log query strings, IPs, keys, long URLs
- Rename a metric without updating alerts
