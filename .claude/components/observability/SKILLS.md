# observability — playbooks

## view logs locally
App: console only, no file (`logback-spring.xml`) — wherever it was started (terminal, `run-local.*`, or `docker logs -f <container>`).
Backing services: `docker compose logs -f` (or `mysql`/`redis`/`rabbitmq` for one). Match a failed request to its server-side line by
`requestId` (`X-Request-Id`), never by other detail — see README "Viewing logs".

## add a metric
Register (pre-register counters at 0), add to ObservabilityIT, reference from an alert if actionable.

## add an alert
Edit ops/prometheus-alerts.yml; ObservabilityIT verifies the metric exists.

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
