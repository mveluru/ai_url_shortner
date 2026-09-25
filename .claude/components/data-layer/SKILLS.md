# data-layer — playbooks

## add a Flyway migration
New `V{n}__desc.sql`, expand-only; index adds use `ALGORITHM=INPLACE, LOCK=NONE`; extend MigrationIT; provide a rollback note.

## test a collation change
Insert `abc123` and `ABC123`; both must succeed (MigrationIT).

## run this component's tests
`./mvnw test` for unit tests; `./mvnw verify -Dit.test=<Name>IT` for one integration test (Docker required).
