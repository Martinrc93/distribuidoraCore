# Git workflow

## Ramas

Se recomienda:

```text
main
feature/<short-description>
fix/<short-description>
docs/<short-description>
```

No trabajar directamente sobre `main` salvo cambios documentales triviales y
acordados.

## Commits

Usar mensajes claros y pequeños:

```text
feat: add customer account payments
fix: prevent payment over allocation
docs: document order confirmation flow
test: cover stock cancellation movement
```

Cada commit debe representar un cambio coherente y compilable cuando sea
posible.

## Pull requests

Una pull request debe incluir:

- Resumen del cambio.
- Motivo.
- Alcance funcional.
- Tests ejecutados.
- Migraciones.
- Cambios de API.
- Riesgos y decisiones pendientes.

Los cambios de dominio deben actualizar la documentación funcional y los ADR
cuando modifiquen una decisión arquitectónica.

## Revisión

La revisión debe verificar:

- Límites de módulos.
- Autorización backend.
- Integridad transaccional.
- Tratamiento de errores.
- Auditoría.
- Tests de regresión.
- Ausencia de secretos.

## Versionado de API

Cambios incompatibles requieren una nueva versión de `/api/vN`. Cambios
compatibles deben documentarse en OpenAPI y mantener comportamiento anterior.
