# shortener-service — playbooks

## add a validation rule
Add to the validator, map to an existing ErrorCode, add a row to the CreateUrlIT/validator test with @Covers, update openapi.yaml if the message contract changes.

## debug an unexpected 409
Check `UniqueConstraint.of()`: SHORT_CODE ⇒ ALIAS_TAKEN; FINGERPRINT ⇒ replay; UNKNOWN ⇒ 500. `mvn verify -Dit.test=CreateUrlIT`.

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
