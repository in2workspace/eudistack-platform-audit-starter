# Changelog

All notable changes to `auditoria-spring-boot-starter` are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **DT-002 — starter de la bandeja de salida de auditoría** (`com.eudistack:auditoria-spring-boot-starter`):
  - Repositorio propio con build Gradle, checkstyle, cobertura mínima del 80 % y CI en GitHub Actions.
  - Autoconfiguración inerte por defecto: sin `auditoria.publisher.enabled` ni `auditoria.dispatcher.enabled`
    no registra ningún bean (AD-8).
  - Propiedades `auditoria.*` de §3.8 con sus valores por defecto, validadas al arrancar cuando algún
    interruptor está activo.
  - Publicación en `mavenLocal`. Decisiones de implementación en `docs/decisions.md`, plan en `docs/tasks.md`.
  - API para los productores: `AuditFact` (con `details` normalizado y sin nulos), `AuditDraft`
    (`success` / `denied` / `failure`), `AuditContext`, `AuditActor`, el evento canónico `AuditEvent` (contexto,
    `source` por defecto y comprobación de tenant EC-08), las interfaces `AuditPublisher` /
    `ReactiveAuditPublisher` y los puertos `AuditContextResolver`, `ReactiveAuditContextResolver`,
    `AuditAccessTokenProvider` y `ReactiveAuditAccessTokenProvider`.
  - `AuditDraftGuard`: rechaza claves de secreto y valores con forma de JWT (RQ-10).
  - `AuditEventJson`: el payload con la forma de `AuditEventInput`, validado contra el OpenAPI de la API.
  - Tabla `public.audit_outbox` (`db/audit-outbox/V1__public_audit_outbox.sql`), creada una sola vez al arrancar
    por `JdbcAuditOutboxSchema`: no hace nada si ya existe y serializa los arranques simultáneos con un bloqueo
    de PostgreSQL.
  - Puerto `OutboxStore` y `JdbcOutboxStore` para productores servlet: insert en la transacción de negocio,
    reclamación con `FOR UPDATE SKIP LOCKED`, reclaim del lease, borrado, reprogramación, cuarentena y suelta
    de las filas de la instancia, con el tiempo del reloj de la base de datos. El SQL (`OutboxSql`) es el que
    usarán también los adaptadores R2DBC y JPA.
  - Tests de integración con PostgreSQL 17 (Testcontainers). La cobertura mínima cuenta las dos suites.
  - Productores reactivos: puerto `ReactiveOutboxStore` y `R2dbcOutboxStore`, con la tabla creada por R2DBC
    (`R2dbcAuditOutboxSchema`) para servicios sin `DataSource`.
  - Productores JPA: `JpaOutboxStore` con consultas nativas y sin `@Entity`.
  - Selección del store según el productor: reactivo → R2DBC; servlet con JPA → JPA; servlet sin JPA → JDBC
    (EC-14). Los tres stores pasan el mismo test de contrato.
  - Contexto de auditoría (tenant, organización, actor y `correlation_id`): en una petición lo resuelve el
    `AuditContextResolver` / `ReactiveAuditContextResolver` del productor, una sola vez y solo cuando se lee,
    sin depender del orden de los filtros. En procesos sin petición se abre con `AuditContexts.system` o
    `ReactorAuditContexts.system`, y `withCorrelationId` lo correlaciona con la operación.
