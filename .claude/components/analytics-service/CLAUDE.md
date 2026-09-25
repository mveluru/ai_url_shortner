# analytics-service

**Responsibility:** Async click pipeline and stats API.

**Key classes / files:** `ClickPublisher`, `ClickSender`, `ClickEventFactory`, `IpHasher`, `ClickConsumer`, `AggregationService`, `AggregationHeartbeat`, `QueueMonitor`, `StatsService`, `RetentionJob`

**Invariants**
- publish never blocks/throws; bounded in-flight; zero retries
- consumer: dedup by event_id via INSERT IGNORE, ack after commit; broker redelivery → DLQ
- daily UTC buckets; referrer stored as host; 90-day retention
- stats on deactivated code → 404 (E20)

**Do NOT**
- Aggregate inline in the redirect path (§15.3)
- Persist raw IPs
- Retry publish
