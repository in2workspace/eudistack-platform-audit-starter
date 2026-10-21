# eudistack-platform-audit-starter — repo guide

Spring Boot starter `com.eudistack:auditoria-spring-boot-starter` (DT-002): the transactional outbox that producers
(Issuer, Verifier, EBW…) embed to deliver audit events to the Audit API (`eudistack-core-audit`,
`POST /api/v1/events`). A library, not a service: it runs inside the producer's process, database and transactions.

The specification (DT-002, catalogue, OpenAPI) lives in `../eudistack-platform-dev/docs/features/EUD-244-api-auditoria/`.
Read [docs/decisions.md](docs/decisions.md) before changing anything structural: it records where the
implementation fills gaps of DT-002. The work plan and its status is [docs/tasks.md](docs/tasks.md).

## Invariants

- Inert by default (AD-8): with `auditoria.publisher.enabled` and `auditoria.dispatcher.enabled` off, the
  auto-configuration registers no bean at all.
- Package root `com.eudistack.auditoria`. `domain` imports no framework; adapters live in their own package per
  runtime (reactive R2DBC/WebClient, servlet JDBC/RestClient, JPA).
- Every integration (R2DBC, JDBC, JPA, WebFlux, MVC, Spring Security, Micrometer) is an optional dependency: a
  producer only loads its own.
- `stage` requires an active local transaction (EC-09); the starter never opens one itself.
- No HTTP call to the Audit API on the business path: only the dispatcher talks to it, after commit.
- All outbox SQL uses the qualified name `public.audit_outbox` (EC-13).
- Never log event payloads, `details`, actors or tokens. No `tenant` label on metrics.
- Time through a `Clock`.

## Commands

| Task | Command |
|------|---------|
| Build, all tests, checkstyle, coverage gate | `./gradlew build` |
| Unit tests only | `./gradlew test` |
| Integration tests (Docker) | `./gradlew integrationTest` |
| Install the jar for local producers | `./gradlew publishToMavenLocal` |
