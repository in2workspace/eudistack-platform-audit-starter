# eudistack-platform-audit-starter

Starter de Spring Boot `com.eudistack:auditoria-spring-boot-starter`: la bandeja de salida (*transactional outbox*)
que embeben los servicios productores de EUDIStack (Issuer, Verifier, EBW…) para entregar sus eventos de auditoría
a la [API de Auditoría](https://github.com/in2workspace/eudistack-core-audit) (`POST /api/v1/events`).

- El caso de uso guarda el evento en `public.audit_outbox`, **en la misma transacción** que el cambio de negocio.
- Un dispatcher en segundo plano, dentro del mismo servicio, lo envía a la API después del commit y lo borra
  cuando la API lo confirma.
- La operación de negocio nunca espera a la API ni falla si la API está caída (EUD-244 FR-09).

Diseño: DT-002, en `eudistack-platform-dev/docs/features/EUD-244-api-auditoria/`. Decisiones de implementación en
[`docs/decisions.md`](docs/decisions.md) y plan de trabajo en [`docs/tasks.md`](docs/tasks.md).

> **Estado:** en construcción. Todavía no está listo para usarse en un productor.

## Activación

Añadir la dependencia no cambia nada: los dos interruptores están apagados por defecto.

| Propiedad | Defecto | Uso |
|-----------|---------|-----|
| `auditoria.publisher.enabled` | `false` | Encola eventos en `public.audit_outbox` |
| `auditoria.dispatcher.enabled` | `false` | Envía la bandeja a la API |
| `auditoria.source` | — | `source` de los eventos del servicio. Obligatoria con el publicador |
| `auditoria.client.base-url` | — | Origen del host interno de la API, sin path. Obligatoria con el dispatcher |

El resto de propiedades, con sus valores por defecto, están en DT-002 §3.8.

## Comandos

| Tarea | Comando |
|-------|---------|
| Build, todos los tests, checkstyle y cobertura | `./gradlew build` |
| Solo tests unitarios | `./gradlew test` |
| Solo tests de integración (Testcontainers, requiere Docker) | `./gradlew integrationTest` |
| Publicar en el repositorio Maven local | `./gradlew publishToMavenLocal` |

## Licencia

[Apache License 2.0](LICENSE).
