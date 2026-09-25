# observability — playbooks

## add a metric
Register (pre-register counters at 0), add to ObservabilityIT, reference from an alert if actionable.

## add an alert
Edit ops/prometheus-alerts.yml; ObservabilityIT verifies the metric exists.

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
