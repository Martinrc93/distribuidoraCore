# Rollback de aplicación y recuperación de base

## Rollback de aplicación

1. Detener el despliegue y conservar logs, request IDs y versión afectada.
2. Identificar si las migraciones nuevas son compatibles hacia atrás.
3. Si son compatibles, volver al artefacto anterior; no revertir migraciones
   Flyway ni ejecutar DDL manual.
4. Si no son compatibles, pausar escrituras, tomar un backup cifrado, restaurar
   a una base nueva y verificarla antes de redirigir `DB_URL`.
5. Ejecutar smoke de login, lectura de ventas, stock, pagos y cuenta corriente.
6. Reabrir escrituras y revisar métricas HTTP, salud PostgreSQL y backlog outbox.

Los cambios comerciales se corrigen con operaciones compensatorias auditadas;
no se reescribe ni elimina el ledger append-only.

## Recuperación de PostgreSQL

- Seguir [`postgres-backup-restore.md`](postgres-backup-restore.md) y restaurar
  siempre primero en una base nueva.
- Comparar la migración Flyway, conteos de usuarios/ventas y saldos antes de
  cambiar el backend a la base recuperada.
- Mantener la instancia original sin escrituras hasta validar la recuperación.
- Rotar secretos si el incidente pudo exponerlos y registrar un postmortem.

No hay migraciones Flyway hacia atrás. Una reversión de esquema se realiza con
una migración correctiva hacia adelante o recuperando una base validada.
