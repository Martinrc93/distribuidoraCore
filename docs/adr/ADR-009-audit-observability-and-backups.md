# ADR-009: Auditoría, observabilidad y backups iniciales

## Status

Accepted

## Context

El sistema maneja precios, descuentos, stock, pagos, deuda, usuarios y
entregas. Necesita trazabilidad de negocio y una base operativa suficiente sin
incorporar inicialmente una plataforma completa de métricas y logs.

## Decision

Auditoría:

- Se registrarán operaciones de seguridad, usuarios, vendedores, pedidos,
  ventas, stock, pagos, deuda, listas y entregas.
- Los eventos de auditoría serán inmutables.
- Se guardará usuario, fecha, operación, recurso, identificador, resultado y
  correlation ID.
- El administrador podrá consultar la auditoría.
- Bloqueos y desbloqueos de usuarios serán auditados.

Observabilidad inicial:

- Spring Boot Actuator.
- Health checks de aplicación y PostgreSQL.
- Logs estructurados.
- Métricas básicas de errores, latencia y disponibilidad.
- Alertas por email.
- No se incorporarán inicialmente Prometheus, Grafana ni Loki.

Alertas iniciales:

- Aplicación caída.
- PostgreSQL no disponible.
- Backup no generado o inválido.
- Poco espacio en disco.
- Errores `5xx` sostenidos.
- Outbox acumulada.
- Fallos de email.

El email de alertas será configurable inicialmente mediante CLI de
administración.

Backups:

- Backup PostgreSQL diario programado.
- Retención de dos backups diarios.
- Retención de un backup mensual.
- Archivos cifrados.
- Verificación de existencia y tamaño.
- Almacenamiento interno en la primera fase.
- No depender únicamente del mismo volumen de PostgreSQL cuando sea posible.
- Prueba de restauración posterior.

Procedimiento mínimo de desastre:

1. Declarar el incidente.
2. Detener escrituras si la instancia todavía responde parcialmente.
3. Provisionar PostgreSQL compatible.
4. Restaurar el backup válido más reciente.
5. Verificar integridad de usuarios, ventas, stock, pagos y deuda.
6. Ejecutar migraciones pendientes.
7. Rotar credenciales si corresponde.
8. Validar login y operaciones críticas.
9. Habilitar nuevamente las escrituras.
10. Registrar el incidente y ejecutar un postmortem.

## Alternatives considered

### Prometheus, Grafana y Loki desde el inicio

Postergados para evitar infraestructura adicional antes de necesitarla.

### Solo backups manuales

Descartados por riesgo operativo y falta de repetibilidad.

### Guardar backups únicamente en el mismo disco

No recomendado, aunque será el punto de partida operativo. La evolución deberá
incorporar almacenamiento externo o un segundo destino.

## Consequences

### Positivas

- Trazabilidad de operaciones sensibles.
- Operación inicial simple.
- Recuperación documentada.
- Bajo costo de infraestructura.

### Negativas

- La observabilidad inicial será limitada.
- El backup interno no protege completamente contra pérdida del servidor.
- El equipo debe verificar restauraciones, no solo generación de archivos.

## Implementación actual (2026-09-24)

- Consola en formato ECS JSON; `X-Request-Id` validado y agregado a MDC.
- Actuator expone health/info/metrics; `http.server.requests` registra latencia
  e histogramas. Métricas outbox: pendientes, procesados, fallidos, agotados y
  duración de lote.
- CI de backend con servicio PostgreSQL 16 y pruebas opt-in habilitadas; primera
  ejecución remota exitosa para `2d1decf` ([run 35957213828](https://github.com/Martinrc93/distribuidoraCore/actions/runs/35957213828), 2026-09-24).
- Scripts PowerShell para backup custom cifrado AES-256-CBC + HMAC-SHA256,
  retención de dos días y doce cortes mensuales, tarea diaria y prueba de
  restauración/tamper en una base descartable.
- Solicitudes terminales de notificación y eventos outbox se purgan luego de
  90 días por defecto; auditoría enmascarada se conserva.
- Se replicó un backup a la carpeta local de OneDrive; HMAC y restauración
  desde esa copia pasaron. La tarea diaria de Task Scheduler se corrigió y quedó
  habilitada tras una corrida de control con resultado `0`; el backup nuevo
  también pasó restore/tamper en PostgreSQL descartable. Se corrigió la lectura
  de Cloud Files: `0x00000009` es placeholder + `InSync`; el usuario confirmó
  que ve en OneDrive el archivo de las 05:55:56. KeePassXC 2.7.12 portable se
  instaló con firma/hash verificados. Tras corregir stdin en Windows PowerShell
  5.1 y guardar la entrada en la raíz (sin depender del grupo inexistente
  `Recovery`), el usuario confirmó `RESULT=OK`; la bóveda real existe, la entrada
  se leyó de vuelta sin imprimir secretos y Cloud Files devolvió
  `0x00000009` (`PLACEHOLDER` + `IN_SYNC`). La bóveda sincroniza con OneDrive;
  la contraseña maestra queda bajo custodia del usuario fuera de OneDrive. La
  restauración descifra temporalmente en
  `%TEMP%` bajo ACL del usuario operativo y requiere volumen cifrado.
- Nota histórica de revisión (2026-09-24): desde la sesión de verificación no
  se obtuvo acceso al listado cloud; ese dato quedó superado por la confirmación
  posterior del usuario de que el backup de las 05:55:56 aparece en OneDrive.
  KeePassXC se instaló después para reemplazar la dependencia exclusiva de DPAPI.

## Pending decisions

- Mantener la contraseña maestra de KeePassXC bajo custodia del usuario fuera
  de OneDrive.
- Frecuencia de pruebas de restauración.
- Retención de auditoría.
- Incorporación futura de métricas y logs centralizados.
