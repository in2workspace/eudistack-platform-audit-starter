# auditoria-spring-boot-starter — Plan de trabajo

Bloques de implementación del starter del DT-002. Cada bloque cierra con su checkpoint en verde y un commit.
Las decisiones que se toman por el camino están en [`decisions.md`](decisions.md).

Rama: `feature/EUD-4-auditoria-spring-boot-starter`.

| Bloque | Contenido | Checkpoint | Estado |
|--------|-----------|------------|--------|
| B0 · Esqueleto | Repositorio propio (D-S1): build Gradle independiente, wrapper, checkstyle sin avisos, cobertura mínima del 80 %, CI en GitHub Actions, Dependabot. `AuditoriaProperties`, autoconfiguración condicionada a los interruptores, `decisions.md`, este plan, CHANGELOG | `./gradlew build` en verde. Interruptores en `false`: ningún bean. `publishToMavenLocal` | Hecho |
| B1 · Dominio y puertos | `AuditFact`, `AuditDraft`, `AuditResult`, `AuditContext`, `AuditActor`, modelo `AuditEventInput`, `AuditDraftGuard`. Puertos `OutboxStore`, `AuditHttpClient`, `AuditContextAccessor`, `OutboxWakeup`, `AuditContextResolver`, `ReactiveAuditContextResolver`, `AuditAccessTokenProvider` | `AuditDraftGuardTest`, serialización contra `AuditEventInput` del OpenAPI, test de arquitectura (dominio sin Spring). `publishToMavenLocal` y aviso al equipo de instrumentación | Pendiente |
| B2 · Esquema + JDBC | `db/audit-outbox/V1__public_audit_outbox.sql`, inicializador sobre `public`, `JdbcOutboxStore` | `JdbcOutboxStoreIT`, `OutboxSkipLockedIT`, `OutboxLeaseReclaimIT`, `releaseOwned`, una sola tabla (R-1) | Pendiente |
| B3 · R2DBC + JPA | `R2dbcOutboxStore`, `JpaOutboxStore`, test de contrato común a los tres stores | `R2dbcOutboxStoreIT`, `JpaOutboxStoreIT` | Pendiente |
| B4 · Contexto | `ReactorAuditContextAccessor`, `ThreadLocalAuditContextAccessor`, filtro del starter con resolución perezosa (D-S6), `withCorrelationId` (D-S7), `AuditContexts.system` | `ReactorAuditContextAccessorTest`, `ThreadLocalAuditContextAccessorTest`, `AuditContextScopeTest`, orden de filtros indiferente | Pendiente |
| B5 · `stage` | `AuditPublisher` / `ReactiveAuditPublisher`, pasos 1–7, transacción obligatoria (EC-09), `ReactiveOutboxWakeup`, `AfterCommitOutboxWakeup` | Rollback conjunto, EC-08, EC-09, EC-10. Patrón de D-S8: el evento de fallo sobrevive en la transacción corta del productor | Pendiente |
| B6 · Clasificación | `@AuditUseCase`, `AuditUseCaseAspect`, `AuditUseCaseOperator`, `audit_outbox_missing_draft`. Comportamiento en caminos con rollback (D-S8) | Success, Denied, Failure, `Mono`, sin borrador | Pendiente |
| B7 · Cliente HTTP | `WebClientAuditHttpClient`, `RestClientAuditHttpClient`: cabeceras explícitas, timeouts 1 s / 2 s, refresco en 401 | `AuditHttpClientHeadersTest` (WireMock), `AuditApiContractIT` contra el `openapi.yaml` de la API | Pendiente |
| B8 · Lógica del dispatcher | `OutboxDispatcher`: respuesta → acción, backoff con jitter y `Retry-After`, bisección, cuarentena y salvaguarda (D-S11) | `OutboxDispatcherTest`, `OutboxPoisonBatchIT` | Pendiente |
| B9 · Bucle y ciclo de vida | `DrainLoop`: espera o señal, relectura con lote de 100, reclaim, un vaciado por pod, parada ordenada con `releaseOwned` | `DrainLoopTest`, `OutboxShutdownReleaseIT`, API apagada → fila `PENDING`. Prueba de punta a punta contra la API de `eudistack-core-audit` en Docker (token de `devTokens`) | Pendiente |
| B10 · Observabilidad y salud | `AuditObservations`, spans `audit.outbox.stage` / `audit.outbox.dispatch`, `traceparent` guardado y restaurado, métricas, gauges, MDC, `AuditOutboxHealthIndicator` | `AuditObservabilityTest`: sin etiqueta `tenant`, sin payload, sin bearer | Pendiente |
| B11 · Cierre | Tests de selección de adaptador (R2DBC+JDBC → R2DBC; solo JDBC → JDBC), p95 del insert, guía de integración, CHANGELOG, versión | Definition of Done | Pendiente |
