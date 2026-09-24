# Operación de PostgreSQL: backup y restauración

## Requisitos y secretos

Los scripts requieren PowerShell 7 y `pg_dump`, `pg_restore`, `createdb`,
`dropdb` y `psql` de una versión PostgreSQL compatible con el servidor. Se puede
definir `PG_BIN` si los ejecutables no están en `PATH`.

La clave se obtiene en este orden: variable `BACKUP_ENCRYPTION_KEY` (Base64 de
64 bytes aleatorios) o almacén local DPAPI creado para la cuenta Windows actual
con `scripts/initialize-backup-key.ps1`. DPAPI protege la clave en el host, pero
no permite recuperarla al perder el perfil/equipo. Antes de depender de los
backups para recuperación ante desastre, exportar/escrowar la clave en un gestor
de secretos independiente; nunca guardar el material en claro junto a los
backups o en el repositorio. Configurar también en el entorno del usuario que
ejecuta la tarea programada:

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
./scripts/register-postgres-backup-task.ps1 -Database distribuidora `
  -PgHost localhost -Username distribuidora `
  -PgBin 'C:\ruta\a\PostgreSQL\bin' `
  -OutputDirectory "$env:LOCALAPPDATA\Distribuidora\Backups" `
  -ExternalOutputDirectory 'C:\ruta\a\destino\sincronizado'
```

La tarea se programa diariamente a las 03:00 bajo el usuario actual con token
interactivo; el usuario debe haber iniciado sesión y PostgreSQL debe estar
disponible. En 2026-09-24 se corrigió el paso de argumentos a PowerShell usando
una acción `-EncodedCommand`, y el wrapper ahora genera un log nuevo por
ejecución. El contexto de Task Scheduler no veía el almacén DPAPI existente;
se materializó el mismo blob cifrado en el perfil de la tarea, sin regenerar la
clave, con ACL limitada al usuario y SYSTEM.

La ejecución programada de control terminó con código `0` a las 05:55. El
backup local y su mirror en el directorio externo de OneDrive configurado
pasaron HMAC y la prueba completa de restauración/tamper en una base descartable
(Flyway V7). La tarea quedó habilitada para la siguiente ejecución diaria a las
03:00. Los logs se guardan por ejecución en
`%LOCALAPPDATA%\Distribuidora\Logs\postgres-backup-task-<runId>.log`.

El canal `Microsoft-Windows-TaskScheduler/Operational` continúa deshabilitado:
Windows denegó su activación/consulta desde esta sesión. La causa se aisló con
tareas de prueba y el resultado/log del job. La observación Cloud Files
`0x00000009` se había interpretado incorrectamente como parcial/no sincronizado.
Según la definición oficial de Windows, `0x1` es `PLACEHOLDER` y `0x8` es
`IN_SYNC`; por tanto `0x00000009` representa placeholder en sincronía. El
marcador temporal de diagnóstico se eliminó. La confirmación posterior del
archivo en OneDrive se registra abajo.

Revisión adicional del 2026-09-24: el backup más reciente está presente en el
directorio local configurado (1.001.256 bytes) y su HMAC volvió a validarse. El
usuario confirmó que lo ve en OneDrive a las 05:55; junto con el estado Cloud
Files `0x00000009` (placeholder + `InSync`), esto confirma la copia externa.
KeePassXC 2.7.12 se instaló en modo portable con firma y hash oficiales
verificados. Se corrigieron el stdin de Windows PowerShell 5.1 y la ruta de la
entrada (la raíz de la bóveda, sin depender de un grupo `Recovery`). El usuario
confirmó `RESULT=OK`; la bóveda real existe (2.110 bytes) y la entrada se leyó
de vuelta sin imprimir el secreto.
Windows Cloud Files informó estado `0x00000009`: `PLACEHOLDER` + `IN_SYNC`, por
lo que la bóveda está sincronizada en OneDrive. La contraseña maestra no se
guardó en el repositorio ni junto a la bóveda; su custodia fuera de OneDrive
queda a cargo del usuario.

La retención deja el backup más reciente por cada uno de los dos días más
recientes y un backup por mes durante los últimos doce meses. El directorio
local no cubre pérdida del mismo equipo o disco.

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
