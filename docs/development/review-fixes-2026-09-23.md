# Correcciones posteriores a la revisión técnica

Fecha: 2026-09-23

## Cambios aplicados

- La reasignación individual de pedidos solo admite pedidos `CONFIRMED`. La opción `onlyPending=false` se rechaza; nunca se actualizan pedidos entregados o cancelados.
- La lectura del refresh token usa bloqueo pesimista por fila para que dos renovaciones simultáneas no emitan sucesores independientes.
- El reuso que revoca todas las sesiones se identifica por `replaced_by`. Un token revocado por logout se rechaza sin cerrar otras sesiones.
- Los access JWT incluyen la versión de sesión del usuario. El filtro compara la versión y el estado actuales; bloquear, desbloquear o revocar sesiones incrementa la versión e invalida los JWT anteriores.
- Los comandos de producto aceptan `categoryId` y `brandId` opcionales. Las referencias deben apuntar a entidades activas; las lecturas exponen esos IDs y muestran el nombre actual de la categoría relacionada. Omitir los IDs en una actualización conserva las referencias existentes.
- La baja de marcas y categorías por `DELETE` es lógica (`INACTIVE`). No existe borrado físico ni respuesta `409` por relaciones; las referencias a productos se mantienen.
- Marcas y categorías no tienen campos `description` ni `updated_at` en el esquema ni en sus DTOs; no forman parte del alcance implementado actualmente.
- Los tokens de activación vencen a los 30 minutos, según la implementación y ADR-004. La activación devuelve `204`; el usuario inicia sesión por separado.

## Contratos relevantes

`POST /api/sellers/reassign-orders` acepta `targetSellerId`, `orderIds` y `onlyPending` opcional. `onlyPending` se omite o se envía como `true`; `false` devuelve `400`.

`POST /api/products` y `PUT /api/products/{id}` admiten `categoryId` y `brandId` opcionales además de los campos existentes. Cuando se informan, ambas entidades deben estar activas.

## Verificación

La suite general `mvn test` terminó con **268 tests, 0 fallos, 0 errores y 7 omitidos**; los omitidos pertenecen a la clase PostgreSQL opt-in, que se ejecuta separadamente. Los otros 261 tests pasaron con H2 y mocks.

Se verificó también contra PostgreSQL real **16.4** en la base aislada `codex_verify_20260923`:

- Flyway validó y aplicó las 15 migraciones (V1–V15) desde un esquema vacío.
- Hibernate inició con `ddl-auto=validate` y validó las entidades contra PostgreSQL.
- Spring Boot inició con el servidor HTTP activo y `/actuator/health` devolvió `UP`.
- Se confirmaron los nueve schemas de módulos y las 15 entradas exitosas en `flyway_schema_history`.
- El servidor PostgreSQL se detuvo limpiamente al finalizar. La base original `distribuidora` no se modificó.
- La prueba opt-in `PostgresBackendFixesIntegrationTest` ejecutó **7 casos, 0 fallos y 0 errores** contra PostgreSQL 16.4. Además de los casos de sesión, reasignación y catálogo, incluye límite acumulado y concurrencia de devoluciones, más rollback transaccional si falla la reposición de stock.

Para repetir los casos funcionales, configurar `POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME` y `POSTGRES_TEST_PASSWORD` con una base PostgreSQL **desechable** y ejecutar `mvn -Dtest=PostgresBackendFixesIntegrationTest test` desde `backend`. La prueba aplica las migraciones, crea fixtures con UUIDs aleatorios y los elimina al terminar.

Las pruebas de regresión cubren el lock pesimista del refresh, revocación por versión de sesión, replay/logout, reasignación protegida, referencias de catálogo y devoluciones con concurrencia, topes acumulados y rollback.

## Actualización: edición de pedidos confirmados (2026-09-24)

- Se agregó `PUT /api/orders/{orderId}`, autorizado únicamente para `ADMIN_ALL`,
  para reemplazar líneas de pedidos y ventas `CONFIRMED`.
- Se conservan los pagos, se impide que el nuevo total quede debajo del importe
  pagado y se concilia la deuda de la venta con asientos append-only.
- Se guardan movimientos de inventario compensatorios y auditoría `ORDER_EDIT`
  junto con los snapshots nuevos, en una única transacción.
- La cancelación ahora revierte el efecto neto por producto de los movimientos
  `SALE` y `SALE_CANCELLATION`; así no duplica devoluciones de stock tras editar.
- Contrato y límites: [`docs/api/order-edits.md`](../api/order-edits.md).

Verificación de esta actualización: suite Maven con PostgreSQL opt-in: **272
tests, 0 fallos, 0 errores y 0 omitidos**. PostgreSQL 16.4 ejecutó 10 casos
funcionales, incluidos edición, conciliación de saldo y edición seguida de
cancelación. No se requirió permiso adicional para esta base aislada.

## Actualización: cobros durante la entrega (2026-09-24)

- La entrega acepta importes `CASH` y `BANK_TRANSFER`; la referencia es opcional
  y queda guardada en `payment.payments.transfer_reference` (migración V16).
- En una misma transacción, aumenta `sale.paid`, registra `CREDIT` por lo
  cobrado, reduce el balance del cliente y conserva el saldo restante en cuenta
  corriente. Si el monto excede lo pendiente, no persiste el intento ni ningún
  efecto parcial.
- El detalle de ventas y las consultas de pagos exponen la referencia. Los
  intentos `FAILED` rechazan cobros.
- Contrato: [`docs/api/delivery-collection.md`](../api/delivery-collection.md).

Verificación: suite Maven con PostgreSQL opt-in: **278 tests, 0 fallos, 0
errores, 0 omitidos**; 12 casos funcionales PostgreSQL 16.4 y Flyway V1–V16.
Los fixtures PostgreSQL se limpiaron al completar las pruebas.

## Actualización: imputación específica y FIFO (2026-09-24)

- Se añadió `POST /api/customers/{customerId}/account-payments`. Al informar
  `saleId`, imputa a esa venta; sin `saleId`, distribuye por FIFO entre las
  ventas más antiguas.
- V17 incorpora el permiso `SALE_PAYMENT` para sellers. El acceso se limita a
  clientes asignados; administración conserva `ADMIN_ALL`.
- Cada asignación crea su fila de pago y un crédito append-only, incrementa
  `sale.paid`, reduce el balance del cliente y registra `ACCOUNT_PAYMENT_APPLY`.
- Locks por venta y cliente serializan pagos concurrentes; se rechazan montos
  superiores a la deuda global o específica.
- Contrato: [`docs/api/account-payments.md`](../api/account-payments.md).

Verificación PostgreSQL 16.4: 15 pruebas funcionales pasan, incluyendo FIFO,
asignación específica, deuda insuficiente y dos cobros simultáneos contra la
misma deuda, de los cuales solo uno se aplica. La suite completa quedó en
**284 tests, 0 fallos, 0 errores y 0 omitidos**, con PostgreSQL opt-in activo.

## Actualización: límite de crédito global (2026-09-24)

- V18 añade el ajuste persistente global en `app.business_settings` y snapshots
  de límite y saldo proyectado en cada venta.
- `GET/PUT /api/settings/credit-limit` requiere `ADMIN_ALL`; enviar `null`
  desactiva el límite.
- Al confirmar, el backend compara el saldo actual más la nueva deuda a cuenta.
  El exceso solo advierte y no bloquea la operación; responde con el límite,
  saldo proyectado y monto excedido.
- `CREDIT_LIMIT_WARNING` y la confirmación se guardan de forma transaccional.
  La respuesta idempotente recupera el snapshot y no duplica el evento.
- Contrato: [`docs/api/credit-limit.md`](../api/credit-limit.md).

Verificación PostgreSQL 16.4: 16 casos funcionales de integración; límite
excedido advertido sin bloquear, evento y snapshots persistidos, reintento
idempotente sin auditoría duplicada y confirmación con límite desactivado. La
suite Maven completa quedó en **290 tests, 0 fallos, 0 errores y 0 omitidos**.

## Próximas tareas en orden

1. [x] Completar cobros durante la entrega: método, identificador opcional de
   transferencia y saldo restante a cuenta corriente.
2. [x] Imputar pagos a una deuda específica o mediante FIFO.
3. [x] Configurar límite de crédito global y advertencias auditadas por exceso.
4. [x] Crear outbox transaccional y worker con reintentos e idempotencia.
5. [x] Añadir tickets y flujos configurables de WhatsApp/email con auditoría.
6. [x] Endurecer operación: CI con PostgreSQL, métricas y logs estructurados,
   backups, restauración y rollback.

## Actualización: outbox transaccional (2026-09-24)

- V19 crea `notification.outbox_events` con payload JSONB, clave idempotente,
  estados persistentes e índices para pendientes y leases vencidos.
- La confirmación comercial inserta `ORDER_CONFIRMED` en la misma transacción.
  La clave única es `ORDER_CONFIRMED:<orderId>`.
- El worker programado procesa lotes con `SKIP LOCKED`, lease de dos minutos,
  backoff exponencial y ocho intentos máximos. El fallo queda guardado y el
  evento agotado pasa a `RETRY_EXHAUSTED`.
- La integración con consumidores es al menos una vez; estos deben deduplicar
  por ID estable. El flujo de notificaciones/tickets es la próxima tarea.
- Diseño y límites: [`docs/architecture/integration-architecture.md`](../architecture/integration-architecture.md).

Verificación PostgreSQL 16.4: migración V19, persistencia transaccional,
reintento por fallo de consumidor y despacho final con ID estable. Suite Maven
completa: **293 tests, 0 fallos, 0 errores y 0 omitidos**, 17 integraciones.

## Actualización: tickets y notificaciones (2026-09-24)

- V20 crea solicitudes persistentes e idempotentes; `POST/GET` bajo el pedido
  permiten pedir el envío y consultar `QUEUED`, `SENDING`, `SENT`, `FAILED` o
  `RETRY_EXHAUSTED`, siempre sujeto a ownership.
- Se agregó ticket PDF de 80 mm desde snapshots persistidos. La descarga y el
  envío usan el mismo renderer y no generan archivos persistentes.
- Email y WhatsApp usan endpoints webhook configurados por entorno. El token no
  se almacena en PostgreSQL, el adjunto va en Base64 y el proveedor recibe la
  misma clave idempotente del evento.
- La auditoría registra solicitud e intentos con destinatario enmascarado. La
  tabla de solicitudes conserva email/teléfono para completar reintentos; la
  tarea 6 aplica retención de 90 días a solicitudes terminales.
- Contrato: [`docs/api/notifications.md`](../api/notifications.md).

Verificación PostgreSQL 16.4: Flyway V1–V20, 18 pruebas de integración y suite
Maven completa: **301 tests, 0 fallos, 0 errores y 0 omitidos**. Se verificaron
idempotencia, auditoría, consulta de estado y despacho mediante outbox.

## Actualización: endurecimiento operativo (2026-09-24)

- Se configuró workflow CI para PostgreSQL 16 y ejecución completa de Maven;
  la primera ejecución remota pasó para `2d1decf` ([run 35957213828](https://github.com/Martinrc93/distribuidoraCore/actions/runs/35957213828)).
- Se activaron logs ECS JSON, propagación segura de `X-Request-Id`, métricas
  HTTP y métricas de backlog/resultado/duración del worker outbox.
- Se agregaron scripts de backup cifrado, retención local y registro de tarea
  diaria, junto con restauración validada por HMAC y procedimiento de rollback.
- La prueba de restauración y rechazo de archivo alterado pasó sobre una base
  descartable, que se eliminó al terminar.
- Solicitudes terminales y eventos outbox se purgan tras 90 días por defecto;
  auditoría enmascarada se conserva.
- Estado histórico de la revisión inicial: Task Scheduler devolvió código `1`
  y la tarea se deshabilitó entonces para evitar una programación falsa. La
  incidencia se corrigió en el seguimiento del 2026-09-24 (ver abajo). Durante
  restore se usa un dump temporal descifrado en `%TEMP%`, que requiere volumen
  cifrado y ACL.

Verificación final: suite Maven **301 tests, 0 fallos, 0 errores y 0 omitidos**;
18 pruebas funcionales contra PostgreSQL 16.4 con Flyway V1–V20.

## Complemento de verificación (2026-09-24)

- Se añadió ArchUnit con reglas incrementales para API/infraestructura y
  dominio/capas de entrega. La regla global sin ciclos detectó dependencias
  existentes; quedan registradas en `docs/architecture/module-boundaries.md`.
- Se añadieron APIs para consulta paginada de usuarios, cambio de rol,
  administración de permisos por rol y consulta de roles/permisos, con
  protección del último administrador, invalidación de sesiones y auditoría.
  Contrato: `docs/api/identity-admin.md`.
- Seguimiento operativo posterior (2026-09-24): se corrigió la acción del task
  usando `-EncodedCommand`, se cambió el log a un archivo por ejecución y se
  materializó en el contexto programado el mismo almacén DPAPI cifrado, sin
  regenerar la clave. La corrida del backup programado devolvió `0`; HMAC y
  restauración/tamper desde el archivo nuevo pasaron en PostgreSQL descartable
  (Flyway V7). La tarea diaria quedó habilitada para las 03:00.
- Corrección del estado OneDrive (2026-09-24): `0x00000009` significa
  placeholder + `InSync` (`0x1` + `0x8`), no parcial/no sincronizado. El usuario
  confirmó que ve el backup de las 05:55:56 en OneDrive. KeePassXC 2.7.12
  portable se instaló con hash/firma verificados. Se corrigió stdin en Windows
  PowerShell 5.1 y la ruta hacia el grupo inexistente `Recovery`; el archivo
  incompleto se eliminó y la prueba con datos ficticios pasó. Después, el
  usuario confirmó `RESULT=OK` para el escrow real; la bóveda existe y Cloud
  Files confirmó sincronización (`0x00000009`, `PLACEHOLDER` + `InSync`). La
  activación/consulta del canal Operational de Task Scheduler fue denegada por
  Windows; se usó el log por ejecución para diagnosticar.
- Revisión del 2026-09-24: el backup local más reciente sigue presente y pasó
  HMAC nuevamente; el usuario confirmó su presencia en OneDrive. Se instaló
  KeePassXC 2.7.12 portable para el escrow independiente de DPAPI. Los dos
  intentos fallidos no dejaron archivo; se corrigió el manejo de stdin y la
  entrada a grupo inexistente, y la prueba con datos ficticios pasó. La clave
  no se incluyó en documentación ni repositorio.
- Cierre KeePassXC (2026-09-24): el usuario confirmó `RESULT=OK`; la bóveda real
  existe, la entrada se leyó de vuelta y Cloud Files devolvió `0x00000009`
  (`PLACEHOLDER` + `InSync`). La clave no se escribió en este registro y la
  contraseña maestra queda bajo custodia del usuario fuera de OneDrive.
- Verificación PostgreSQL previa: **314 tests, 0 fallos, 0 errores y 0
  omitidos**, incluidos 19 casos PostgreSQL 16.4/Flyway V1–V20 en una base
  descartable eliminada al terminar.

## Cierre de arquitectura y cobertura HTTP (2026-09-24)

- El handler de `ProductPriceValidationException` se trasladó a
  `catalog.api.CatalogExceptionHandler`; la regla `shared`↛`catalog` impide
  reintroducir la arista que cerraba el ciclo conocido
  `audit → shared → catalog → audit`.
- Los servicios de aplicación dejaron de importar DTOs HTTP. Los módulos
  exponen contratos/resultados de aplicación y sus controllers adaptan las
  respuestas a DTOs de transporte. `application`↛`api` se verifica con
  ArchUnit.
- Se agregaron **14 casos HTTP `MockMvc`** entre marcas, categorías, pagos de
  cuenta corriente, entrega, devoluciones, vendedores y límite de crédito.
  Comprueban binding, respuestas serializadas, códigos HTTP y rechazos de
  validación.
- Pruebas: los ocho tests de controller/arquitectura enfocados pasaron (**46
  tests, 0 fallos**); la suite completa terminó con **328 tests, 0 fallos, 0
  errores y 19 omitidos**. Los omitidos son las pruebas PostgreSQL opt-in,
  porque esta ejecución no tenía `POSTGRES_TEST_URL`; la verificación
  PostgreSQL previa está registrada arriba.

## Revisión completa del grafo modular (2026-09-24)

- `ModuleBoundaryTest` ahora aplica la regla global de slices sin ciclos e
  importa solo clases de producción. Esto evita que clases de test o bytecode
  obsoleto en `target` alteren el análisis.
- Se resolvieron las dependencias de errores específicas que salían de
  `shared`: JWT/filtro y errores de identidad pertenecen a `identity`, el
  cableado de seguridad está en `config`, y los handlers de documentos y
  conflictos de idempotencia viven en `document` y `order`.
- Se documentó el grafo observado en
  `docs/architecture/module-boundaries.md`; `shared` ya no tiene dependencias
  salientes hacia módulos de negocio y todos los slices de producción forman
  un grafo sin ciclos.
- Suite Maven completa: **329 tests, 0 fallos, 0 errores y 19 omitidos**;
  ArchUnit pasó. Los casos omitidos requieren `POSTGRES_TEST_URL`; la ejecución
  con PostgreSQL descartable previa está registrada arriba.

## Cadena de seguridad HTTP (2026-09-24)

- Se fijó en `SecurityConfig` el entry point de autenticación anónima a `401`,
  alineando la respuesta de la cadena de producción con el contrato REST.
- `SecurityChainAuthorizationTest` recorre la configuración de seguridad de
  producción y el filtro JWT con tokens firmados y usuarios activos/versionados.
  Seis pruebas pasan: anónimo `401`, permiso insuficiente `403` y rutas de marca,
  documentos, pagos, entrega, inventario y administración con las authorities
  funcionales actuales. También rechazan cruces entre permisos no relacionados.
- El repositorio de usuarios se simula solo en la matriz rápida. La prueba
  PostgreSQL recorre login HTTP real, authorities de roles persistidos, cambio
  administrativo de rol/permisos, `401` para el JWT anterior y permisos
  actualizados al emitir un JWT nuevo.

## Verificación PostgreSQL descartable (2026-09-24)

- La suite completa se ejecutó con PostgreSQL Portable 16.4 en un cluster nuevo
  bajo `%TEMP%`, enlazado solo a `127.0.0.1:55432`. El cluster persistente del
  proyecto no se inició ni se modificó.
- Flyway aplicó V1–V20 y Hibernate verificó el esquema (`ddl-auto=validate`).
  Pasaron los **21 tests** de `PostgresBackendFixesIntegrationTest`; la suite
  total terminó con **337 tests, 0 fallos, 0 errores y 0 omitidos**.
- En este host `pg_ctl` no pudo iniciar/detener el proceso por restricciones de
  token de Windows. El servidor temporal se inició directamente y se detuvo
  verificando el PID registrado en `postmaster.pid`, el ejecutable y el cluster;
  se comprobó que el puerto quedó cerrado antes de eliminar solo el directorio
  temporal validado. No se usó ni modificó el cluster PostgreSQL persistente.
