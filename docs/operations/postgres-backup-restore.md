# Operación de PostgreSQL: backup y restauración

## Requisitos y secretos

Los scripts requieren PowerShell 7 y `pg_dump`, `pg_restore`, `createdb`,
`dropdb` y `psql` de una versión PostgreSQL compatible con el servidor. Se puede
definir `PG_BIN` si los ejecutables no están en `PATH`.

Configurar en el entorno del usuario que ejecuta la tarea programada:

- `DB_HOST`, `DB_PORT`, `DB_USERNAME` y `DB_PASSWORD`.
- `POSTGRES_DB` para elegir la base por defecto.
- `BACKUP_ENCRYPTION_KEY`: Base64 de 64 bytes aleatorios, separado de los
  backups y del repositorio. Guardarlo en el almacén de secretos del host.

Generar una clave una sola vez y copiarla a un almacén de secretos:

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
```

La clave no se rota sin conservar la clave anterior para los backups históricos.
Cada backup usa AES-256-CBC y HMAC-SHA256 encrypt-then-MAC. El HMAC se verifica
antes de descifrar o modificar una base. `pg_dump` transmite el formato custom
directamente al cifrador; no crea un dump temporal en claro en disco.

## Backup diario y retención

```powershell
./scripts/backup-postgres.ps1 -Database distribuidora
./scripts/register-postgres-backup-task.ps1
```

La tarea se programa diariamente a las 03:00 bajo el usuario actual. Los archivos
se guardan en `backups/`, excluido de Git. La retención deja el backup más
reciente por cada uno de los dos días más recientes y un backup por mes durante
los últimos doce meses. Copiar además los archivos cifrados a un destino externo:
el directorio local no cubre pérdida del mismo equipo o disco.

El script falla de forma no cero si `pg_dump` o el cifrado fallan; la tarea
programada registra el resultado para monitoreo.

## Prueba de restauración

Usar una base fuente de pruebas o producción autorizada. El script crea una base
temporal `codex_restore_*`, restaura ahí, verifica Flyway y la tabla de
solicitudes de notificación, y elimina esa base temporal al finalizar:

```powershell
./scripts/test-postgres-restore.ps1 -SourceDatabase distribuidora
```

La prueba también altera una copia del backup para verificar que un HMAC
incorrecto se rechace antes de escribir en el destino.

Durante la restauración, el archive se descifra en un temporal de usuario para
permitir el acceso aleatorio requerido por `pg_restore`; el script lo elimina
al finalizar. Mantener `%TEMP%` en un volumen cifrado y con ACL del usuario
operativo.

## Restauración operacional

Provisionar primero una base vacía con el rol esperado. El siguiente comando es
destructivo para los objetos dentro de la base destino: requiere confirmación;
`-Force` debe reservarse para automatización o bases descartables.

```powershell
./scripts/restore-postgres.ps1 -BackupPath .\backups\distribuidora-20260924-030000.dcbak `
  -TargetDatabase distribuidora_recovery -Confirm
```

Después de restaurar, ejecutar migraciones pendientes con el backend, comprobar
`/actuator/health`, login, una consulta comercial y consistencia de stock, pagos
y cuenta corriente antes de reabrir escrituras.

## Exposición operativa

`/actuator/health` permanece público para probes de vida y readiness. El endpoint
`/actuator/metrics/**` requiere autenticación; incluye métricas HTTP y métricas
`app.outbox.*` (pendientes, procesados, errores, agotados y duración de lote).
Los logs de consola usan ECS JSON y llevan `requestId` en MDC. Request IDs
externos fuera del conjunto seguro o mayores de 120 caracteres se reemplazan.

Solicitudes terminales (`SENT`, `FAILED`, `RETRY_EXHAUSTED`) purgan el email o
teléfono luego de 90 días por defecto. Configuración: `NOTIFICATION_RETENTION_DAYS`,
`NOTIFICATION_RETENTION_CRON` y `NOTIFICATION_RETENTION_ENABLED`. Auditoría
enmascarada y eventos outbox conservan solo IDs/metadata y no el destinatario.
