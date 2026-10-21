# auditoria-spring-boot-starter — Decisiones de implementación

Starter de la bandeja de salida (outbox) que embeben los productores de eventos de auditoría (Issuer, Verifier,
EBW…) para entregarlos a la [API de Auditoría](https://github.com/in2workspace/eudistack-core-audit). El diseño
es el [DT-002](https://github.com/in2workspace/eudistack-platform-dev/blob/main/docs/features/EUD-244-api-auditoria/DT-002-integracion-productores-auditoria.md)
de `eudistack-platform-dev/docs/features/EUD-244-api-auditoria/`.

Este documento no repite el diseño. Recoge las **decisiones tomadas al implementarlo** cuando el DT no cubre un
caso o no encaja con el código de los productores, y los **hallazgos** sobre el propio DT. Cada decisión respeta
el DT tal como está escrito. El Tech Lead las revisa cuando puede; si no está de acuerdo con alguna, se cambia
aquí y en el código. Mismo criterio que el
[`docs/architecture.md`](https://github.com/in2workspace/eudistack-core-audit/blob/main/docs/architecture.md) de la API.

## 1. Decisiones

| Id | Decisión | Origen |
|----|----------|--------|
| D-S1 | **Repositorio propio, `eudistack-platform-audit-starter`** (público, Apache 2.0), separado de la API. La API es un servicio que se despliega; el starter es una librería que se ejecuta dentro de cada productor, con otra cadencia de versiones. Los une solo el contrato (`openapi.yaml`) y el catálogo. Nombre según `repository-naming.md` (capa `platform`, recursos compartidos). | Confirmado por el Tech Lead. El DT no fija el repositorio; el diseño de la API y su `docs/architecture.md` dejan la bandeja de los productores fuera del servicio. |
| D-S2 | **Coordenadas `com.eudistack:auditoria-spring-boot-starter` y paquete raíz `com.eudistack.auditoria`**, como el DT. | DT-002 §1 y §3.2. Elegido frente a `net.eudistack`, que es el namespace de la otra librería de la organización (`eudistack-verifier-client-java`). Consecuencia en D-S3. |
| D-S3 | **Publicación solo en `mavenLocal`** (`./gradlew publishToMavenLocal`) por ahora. El repositorio remoto queda abierto: la otra librería de la organización publica en Maven Central bajo `net.eudistack`, y Maven Central solo admite un groupId de dominio verificado, que probablemente no es el caso de `com.eudistack`. Alternativa: GitHub Packages, que exige autenticación también para consumir. | Hueco del DT: "starter publicado" sin repositorio de artefactos. **[validar]** |
| D-S4 | **Dependencia base `spring-boot-starter`** y BOM de Boot 3.5.11 (la versión de los productores). Cada integración (R2DBC, JDBC, JPA, WebFlux, MVC, Spring Security, Micrometer) entra como dependencia opcional, para que un productor solo cargue la suya. | DT-002 AD-3 y AD-11. |
| D-S5 | **Interruptores apagados = ningún bean**, ni siquiera el de propiedades: la autoconfiguración entera se condiciona a `auditoria.publisher.enabled` o `auditoria.dispatcher.enabled`. Con uno encendido, la configuración se valida al arrancar (`source` con el publicador; `base-url` como origen sin path con el dispatcher; `lease` mayor que el timeout de lectura de 2 s). `batch-size` por encima de 100 se recorta a 100, no es un error. | DT-002 AD-8, §3.8 y §2.4. |
| D-S6 | **Contexto de una petición: filtro del starter + traductor del productor.** El starter registra un filtro (servlet) o `WebFilter` (reactivo) y gestiona el ciclo de vida del contexto. El productor aporta un bean `AuditContextResolver` / `ReactiveAuditContextResolver` que traduce `Authentication` (+ request) → `AuditContext`. La resolución es **perezosa**: se hace en el primer `stage` de la petición y se reutiliza, así que el orden de los filtros no importa. Si el resolver no da `correlation_id`, el starter genera uno por petición. | El DT (§3.3, §3.4.1, §3.8 paso 5) asume que los filtros existentes dejan `tenant`, `organization`, `correlation_id` y `actor`. Solo dejan el tenant (`TenantDomainWebFilter` en Issuer y EBW, `TenantDomainFilter` en Verifier), `correlation_id` no existe, y `actor`/`organization` salen de claims propios de cada servicio. El `TenantDomainWebFilter` del Issuer no declara orden. Se implementa en B4. |
| D-S7 | **`correlation_id` sobrescribible por el caso de uso** con `AuditContexts.withCorrelationId(id)` / `ReactorAuditContexts.withCorrelationId(id)`. | DT-002 §3.9.1: en una emisión es el id del proceso, que solo conoce el caso de uso. Se implementa en B4. |
| D-S8 | **`Denied`/`Failure` con rollback del negocio: EC-09 sin cambios.** `stage` exige transacción activa y el starter nunca abre una. El productor captura el error fuera de la transacción de negocio y encola el evento en una transacción corta propia (`TransactionalOperator` / `TransactionTemplate`). Un `Success` va siempre en la transacción de negocio. No hay `stageStandalone`. | El DT no explica cómo sobrevive el evento al rollback (AD-6 asume que el aspecto toma el `reason` de la excepción). Detectado por el equipo de instrumentación. El patrón se prueba en B5; el comportamiento de `@AuditUseCase` en esos caminos se decide en B6. |
| D-S9 | **Token M2M: puerto `AuditAccessTokenProvider`** que implementa cada productor. El starter pide el token y lo refresca una vez en 401. | El DT habla del "puerto de credenciales de sistema que el productor ya use frente al Verifier". Ningún productor obtiene hoy tokens del Verifier como cliente. |
| D-S10 | **`auditoria.client.audience` opcional**: vacía, el dispatcher arranca con un WARN. | El DT la hace obligatoria con el dispatcher activo, pero la API no exige audiencia mientras el Verifier no la emita (D-16 de la API, PO-12; PA-03 del DT). |
| D-S11 | **PA-01 provisional: 10 aislamientos por ventana de 10 min**, configurables con `auditoria.dispatcher.quarantine-max-per-window` y `auditoria.dispatcher.quarantine-window` (esta segunda no figura en §3.8). **[validar N]** | DT-002 AD-9 y PA-01. |

## 2. Hallazgos sobre el DT-002

| Id | Hallazgo | Propuesta |
|----|----------|-----------|
| H-S1 | La matriz de tests de §2.3 repite filas (RQ-21…RQ-26), y `OutboxPoisonBatchIT` y `OutboxShutdownReleaseIT` figuran como *Unit* aunque su nombre es de integración. | Se implementan según lo que prueban: los que necesitan PostgreSQL, como IT. |
| H-S2 | §3.7.3 da el SQL de reclamación, reclaim, liberación y cuarentena, pero no el de borrado ni el de reprogramación. | Se definen en B2 con el mismo criterio (nombre cualificado, `clock_timestamp()`, solo filas `IN_FLIGHT` de la instancia). |
| H-S3 | El Verifier no tiene ninguna transacción local (`@Transactional`, `TransactionTemplate`), y `stage` la exige (EC-09). | Afecta a la instrumentación del Verifier, no al starter. |
