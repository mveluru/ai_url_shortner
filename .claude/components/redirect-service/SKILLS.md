# redirect-service — playbooks

## write a failure-injection test for F1
Extend `FailureInjectionIT`: `down(REDIS_PROXY)`, assert 302 + breaker OPEN, `up(...)`, await CLOSED. `mvn verify -Dit.test=FailureInjectionIT`.

## tune a breaker
Edit application.yml AND the design §8.2.1 table; `DesignConformanceTest` will fail until both agree.

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
