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
