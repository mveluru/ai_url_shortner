# security-auth — playbooks

## add an SSRF range
Add to IpClassifier + a parameterized row in IpClassifierTest and UrlValidatorTest.

## change a rate limit
application.yml + design §11.3 + DesignConformanceTest.

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
