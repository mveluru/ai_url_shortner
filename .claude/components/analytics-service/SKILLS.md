# analytics-service — playbooks

## add a stats dimension
Extend AggregationService.process + entity JSON + StatsService + openapi + AnalyticsIT; keep the response additive (non-breaking).

## replay the DLQ
Inspect `url-shortener.clicks.events.dlq` in the RabbitMQ UI; fix cause; shovel back; dedup makes replay safe.

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
