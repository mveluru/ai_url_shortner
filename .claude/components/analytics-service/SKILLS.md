# analytics-service — playbooks

## add a stats dimension
Extend AggregationService.process + entity JSON + StatsService + openapi + AnalyticsIT; keep the response additive (non-breaking).

## replay the DLQ
Inspect `url-shortener.clicks.events.dlq` in the RabbitMQ UI (`localhost:15672`, `guest`/`guest` locally); fix cause; shovel back; dedup makes replay safe.
Queue/consumer counts there beat `docker compose logs rabbitmq` for watching backlog build or drain (F6). Why the queue exists at all: README "Viewing logs".

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
