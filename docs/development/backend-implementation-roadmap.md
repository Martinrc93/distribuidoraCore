# Roadmap de implementación del backend

Este roadmap registra la secuencia de implementación aplicada a los ADR. Las
fases y pendientes históricos se reconciliaron el 2026-09-24 con el estado
actual; la checklist y el estado de funcionalidades son las referencias para
consultar el trabajo backend abierto.

## Fase 0: Plataforma y límites

Aplicados:

- ADR-001: monolito modular y paquetes por módulo.
- ADR-002: PostgreSQL, schemas por módulo, Flyway y `ddl-auto=validate`.
- ADR-009: Actuator, health probes y `X-Request-Id`.

Criterios:

- `docker compose up --build` inicia `db`, `backend` y `frontend`.
- PostgreSQL conserva datos en `postgres-data`.
- Flyway ejecuta V1 y V2 sin intervención manual.
- `/actuator/health` responde correctamente.

## Fase 1: Identity y seguridad

Aplicados:

- ADR-004: usuario local, Argon2id, JWT stateless de 15 minutos y permisos en
  claims.
- ADR-009: auditoría de login con correlation ID.

- Alta/invitación administrativa de usuarios, activación de un solo uso,
  refresh tokens rotativos, bloqueo/desbloqueo y revocación de sesiones.
- Consulta paginada de usuarios y cambio de roles con protección del último
  administrador activo y coherencia con perfiles seller.
- Consulta de roles/permisos y edición transaccional de permisos con protección
  de `ADMIN_ALL`/`USER_MANAGE`, invalidación de sesiones y auditoría; ver
  [`contrato de administración`](../api/identity-admin.md).

Criterios:

- Login correcto devuelve Bearer token.
- Password inválido no revela si el email existe.
- Tres intentos inválidos bloquean al usuario.
- Login exitoso y fallido generan eventos append-only.
- Endpoints privados rechazan requests sin token válido.
- La administración conserva siempre al menos un administrador activo.

## Fase 2: Seller y Customer

Implementado:

- `seller.seller_profiles` asociado opcionalmente a `identity.users`.
- `customer.customers` con vendedor asignado, estado y datos fiscales.
- APIs internas para resolver el vendedor de un cliente.
- Permisos `USER_MANAGE` y acceso de vendedor a clientes asignados.

Criterios:

- Un administrador crea y desactiva vendedores.
- Un cliente puede asignarse a un vendedor.
- Un vendedor no puede leer clientes no asignados.
- Cambios relevantes quedan auditados.

## Fase 3: Catalog y pricing

Aplicado:

- Productos, categorías, marcas y presentaciones en `catalog`.
- Listas normalizadas en `catalog.price_lists` y precios por producto en
  `catalog.product_prices`.
- Diez listas iniciales con identificadores deterministas; `GENERAL`, `LISTA_2`
  y `LISTA_3` activas, y `LISTA_4` a `LISTA_10` inactivas.
- Máximo de diez listas, sin eliminación física, con `GENERAL` como lista
  default.
- Asignación opcional de una lista activa a cada cliente.
- Resolución determinística por lista explícita, lista asignada o fallback a
  `GENERAL`; los cambios de precio tienen efecto inmediato.
- Importes con `NUMERIC(19,4)` y `BigDecimal`.

Ampliaciones incorporadas y completadas (2026-09-24):

- Historial de precios y vigencias futuras, con resolución por fecha comercial,
  cancelación de programaciones y snapshots históricos; V21. Contrato y
  verificación en `docs/api/pricing.md` y `docs/development/backend-checklist.md`.
- Reglas persistidas de descuento de línea y pedido, aplicadas al confirmar y
  editar, con prioridad, vigencia y snapshots; V22. Contrato en
  `docs/api/pricing.md`.

Criterios:

- La lista `GENERAL` existe como fallback.
- Un precio ausente prueba las listas activas anteriores por identificador antes
  de devolver `404`.
- Solo `ADMIN_ALL` puede crear, modificar o desactivar listas y precios, y
  asignar listas a clientes.

Contrato HTTP de Pricing:

```text
POST /api/pricing/lists
{ "code": "CUSTOM", "name": "Clientes" }
-> 201 { "id": "<uuid>" }

PUT /api/pricing/lists/{listId}
{ "name": "Clientes mayoristas" }
-> 204

PATCH /api/pricing/lists/{listId}/status
{ "status": "ACTIVE" }
-> 204

PUT /api/pricing/lists/{listId}/products/{productId}
{ "price": 10.2500 }
-> 204

PATCH /api/customers/{customerId}/price-list
{ "priceListId": "<uuid>" }
-> 204
```

La asignación se limpia con `{ "priceListId": null }`. Los requests de lista
validan `code` no vacío de hasta 40 caracteres, `name` no vacío de hasta 120 y
`status` en `ACTIVE`/`INACTIVE`; el precio es obligatorio, no negativo y usa
hasta cuatro decimales. Las lecturas y la resolución responden `200`.
La resolución devuelve `priceListId`, `priceListCode`, `productId` y
`unitPrice`, y elige lista explícita, lista asignada o `GENERAL`, en ese orden.

La política de errores es explícita: `400 INVALID_REQUEST` para JSON/UUID
malformados o validaciones fallidas; `403 FORBIDDEN` para mutaciones sin
`ADMIN_ALL`; `404 NOT_FOUND` para lista, cliente o producto inexistente y
para un precio ausente en la lista seleccionada y sus listas activas anteriores; y `409 CONFLICT` para código
duplicado, límite total de diez listas, lista inactiva al asignar o cambiar
precios, desactivación de la lista default `GENERAL` o resolución con una lista
inactiva. Una lista inactiva nunca se reemplaza mediante fallback; el fallback
por precio faltante solo usa listas activas con identificador menor.

## Fase 4: Inventory

Implementado y ampliado:

- `inventory.inventory_balances` con saldo actual.
- `inventory.stock_movements` append-only.
- Ajuste manual de stock mediante delta firmado.
- Lock pesimista del balance dentro de la transacción.
- Auditoría del ajuste manual con operación `STOCK_ADJUSTMENT`.
- Depósitos con `CENTRAL` como predeterminado y migración V23 de saldos y
  movimientos existentes.
- Balances por depósito, ajustes y transferencias atómicas con locks ordenados.
- Depósito persistido en pedidos/ventas y conservado por ediciones,
  devoluciones y cancelaciones; lecturas agregadas mantienen el contrato
  anterior.

Evolución completada:

- Deltas de stock por edición/cancelación y flujos de devolución verificados;
  contratos actuales en `docs/api/inventory.md`, `docs/api/order-edits.md` y
  `docs/api/sale-returns.md`.

Criterios:

- Se permiten saldos negativos.
- Cantidades válidas son múltiplos de `0.5`.
- Un movimiento nunca se actualiza ni elimina.
- Un ajuste requiere `STOCK_ADJUST` y evento de auditoría.

## Fase 5: Order, Sale y Payment

Aplicado: una única transacción de confirmación:

```text
request de confirmación
-> Order CONFIRMED
-> Sale
-> movimientos de Inventory
-> Payment o CustomerAccountLedger
```

Implementado:

- Estado `CONFIRMED` y snapshots de pedido/venta.
- Descuentos de línea y totales en cascada.
- Snapshots de líneas y precios.
- Pagos parciales y combinados.
- Cuenta corriente append-only.
- Movimiento de inventario `SALE` con locking pesimista.
- Idempotencia por clave y fingerprint.
- Lectura de detalle por ID o número con totales de venta, pagos y cuenta.
- Validación de `ORDER_CREATE` y overrides con `ADMIN_ALL`.

Completado:

- Las lecturas y operaciones del vendedor se limitan a clientes/pedidos
  asignados; la autorización se aplica en rutas críticas y está cubierta por
  pruebas HTTP y PostgreSQL. Ver `docs/development/backend-functionality-status.md`.

Criterios:

- No se persisten borradores.
- Confirmar es atómico: si falla stock, precio o pago, no se guarda la venta.
- Reintentar con la misma clave y payload no duplica filas y devuelve la respuesta original.

### V7: Delivery lifecycle y cancelación

Aplicado mediante `V7__add_delivery_cancellation_support.sql`:

- La migración V16 agrega `payment.payments.transfer_reference` nullable para
  conservar el identificador opcional de transferencias cobradas al entregar.

- Estados de pedido y venta `CONFIRMED`, `DELIVERED` y `CANCELLED`, con
  `delivered_at` y `cancelled_at`.
- `orders.delivery_attempts` append-only, con número consecutivo, resultado
  `DELIVERED` o `FAILED` y observación obligatoria para fallos.
- Un intento `FAILED` conserva pedido y venta en `CONFIRMED` y permite otro
  intento.
- Un intento `DELIVERED` cambia pedido y venta juntos a `DELIVERED`; ambos son
  terminales. Puede registrar cobros `CASH`/`BANK_TRANSFER` y referencia
  opcional de transferencia en la misma transacción.
- La cancelación solo opera desde `CONFIRMED`, solo la puede ejecutar un
  administrador y rechaza ventas con importe pagado.
- La cancelación revierte cada movimiento `SALE` con un movimiento
  `SALE_CANCELLATION`, crea un `CREDIT` por la deuda pendiente y disminuye el
  saldo del cliente en la misma transacción. Pedido y venta pasan a
  `CANCELLED`.

### Edición administrativa de pedidos confirmados

Aplicada sin migración de esquema mediante `PUT /api/orders/{orderId}`:

- Solo `ADMIN_ALL` puede reemplazar las líneas de una orden y venta en estado
  `CONFIRMED`.
- La operación recalcula precios, descuentos y snapshots; mantiene el importe
  pagado y ajusta la cuenta corriente con un asiento compensatorio append-only.
- Las diferencias de inventario se registran como `SALE` o
  `SALE_CANCELLATION` y quedan enlazadas a la orden.
- La cancelación posterior revierte el saldo neto de movimientos por producto,
  evitando restituir stock de más tras una edición.
- Contrato HTTP y reglas: [`docs/api/order-edits.md`](../api/order-edits.md).
- Pruebas unitarias y PostgreSQL verifican autorización, edición de líneas,
  conciliación de deuda, stock, protección del importe pagado y cancelación
  posterior.

Contrato HTTP:

```text
POST /api/orders/{orderId}/delivery-attempts
{ "result": "FAILED", "observation": "Dirección cerrada" }
-> 204 No Content

POST /api/orders/{orderId}/delivery-attempts
{ "result": "DELIVERED", "observation": null }
-> 204 No Content

POST /api/orders/{orderId}/cancel
-> 204 No Content
```

`delivery-attempts` requiere `SALE_DELIVER` o `ADMIN_ALL`; `cancel` requiere
`ADMIN_ALL`. Al marcar `DELIVERED` se admiten pagos y referencia opcionales,
según el [contrato de cobros](../api/delivery-collection.md). El request de
intento valida `result` no vacío, limitado a 20
caracteres, `observation` opcional de hasta 2000 caracteres y observación no
blanca cuando `result` es `FAILED`.

Errores del contrato:

- `400 INVALID_REQUEST` para resultado inválido, observación fallida ausente,
  JSON inválido o UUID inválido.
- `401` sin JWT o con JWT inválido.
- `403 FORBIDDEN` cuando faltan `ORDER_CREATE`/`ADMIN_ALL` para intentos o
  `ADMIN_ALL` para cancelar.
- `404 NOT_FOUND` para un pedido inexistente.
- `409 CONFLICT` cuando el pedido o venta no están en `CONFIRMED`, cuando se
  cancela una venta pagada o cuando se reintenta una operación terminal.

Criterios V7:

- Un `FAILED` persiste el intento y conserva ambos estados en `CONFIRMED`.
- Un `DELIVERED` sin cobros no modifica pagos ni ledger; si se envían cobros,
  registra pagos y crédito de cuenta corriente de forma atómica.
- Cancelar revierte movimientos `SALE`, agrega `SALE_CANCELLATION`, registra el
  `CREDIT` de cuenta corriente y devuelve el balance del cliente al valor previo.
- Reintentar después de `DELIVERED` o `CANCELLED` es rechazado y no agrega
  movimientos, intentos, créditos ni cambios de balance.
- El smoke reproducible de PostgreSQL verifica estos invariantes sobre el
  volumen persistente de Compose.

### V17: Imputación de pagos de cuenta corriente

Aplicado mediante `V17__add_sale_payment_permission.sql` y el servicio de pagos:

- `POST /api/customers/{customerId}/account-payments` permite imputación a una
  venta específica o FIFO por fecha y UUID.
- Cada porción crea un pago y un asiento `CREDIT`, incrementa `sale.paid` y
  reduce el balance del cliente, todo en una transacción auditada.
- La autoridad `SALE_PAYMENT` se asigna a vendedores y se limita a clientes
  asignados; `ADMIN_ALL` mantiene acceso administrativo.
- Se bloquean primero las ventas afectadas y después el cliente; pruebas reales
  concurrentes confirman que no se aplica dos veces la misma deuda.
- Contrato: [`docs/api/account-payments.md`](../api/account-payments.md).

### V18: Límite de crédito global

Aplicado mediante `V18__add_global_credit_limit.sql`:

- `GET/PUT /api/settings/credit-limit` permite a administradores consultar,
  establecer o desactivar el límite global.
- La confirmación calcula el saldo proyectado del cliente con la nueva deuda a
  cuenta corriente. Si excede el límite, confirma igualmente y devuelve una
  advertencia con límite, saldo proyectado y exceso.
- La venta conserva snapshots del límite y saldo calculados; el evento
  `CREDIT_LIMIT_WARNING` queda auditado en la misma transacción. Un reintento
  idempotente devuelve la advertencia guardada sin duplicar la auditoría.
- Contrato: [`docs/api/credit-limit.md`](../api/credit-limit.md).

Verificación: PostgreSQL 16.4, Flyway V1–V18, 16 casos de integración y suite
Maven completa con 290 tests, 0 fallos, 0 errores y 0 omitidos.

### V19: Outbox transaccional y worker

Aplicado mediante `V19__create_transactional_outbox.sql`:

- La confirmación escribe `ORDER_CONFIRMED` y un payload JSONB mínimo en la
  misma transacción comercial. La clave única `ORDER_CONFIRMED:<orderId>` evita
  duplicados por reintentos de la confirmación.
- Un worker programado reclama hasta 50 eventos cada cinco segundos mediante
  `FOR UPDATE SKIP LOCKED`; leases de dos minutos recuperan eventos abandonados.
- Los consumidores reciben un ID de evento estable. El despacho es al menos
  una vez y los consumidores deben deduplicar por ID.
- Los fallos usan backoff exponencial de 30 segundos hasta seis horas y se
  agotan tras ocho intentos; se conserva el error limitado a 2000 caracteres.
- Se puede desactivar el polling con `OUTBOX_WORKER_ENABLED=false` y modificar
  su frecuencia con `OUTBOX_POLL_INTERVAL_MS`.
- Diseño operativo: [`docs/architecture/integration-architecture.md`](../architecture/integration-architecture.md).

Verificación: PostgreSQL 16.4, Flyway V1–V19, 17 casos funcionales de
integración y suite Maven completa con 293 tests, 0 fallos, 0 errores y 0
omitidos.

### V20: Tickets y solicitudes de notificación

Aplicado mediante `V20__create_notification_delivery_requests.sql`:

- Se agrega `GET /api/orders/{orderId}/documents/ticket`, que genera un ticket
  PDF de 80 mm bajo demanda desde snapshots y aplica ownership.
- `POST /api/orders/{orderId}/notifications` encola solicitudes idempotentes de
  `EMAIL` o `WHATSAPP`, con formato `A4` o `TICKET`. El `GET` anidado permite
  consultar estado e intentos bajo la misma regla de ownership.
- El envío consume outbox con webhooks de proveedores configurados por entorno;
  URL, token y timeout no se guardan en PostgreSQL.
- Se auditan solicitud (destinatario enmascarado), inicio/resultado de cada
  intento y agotamiento de reintentos. La solicitud guarda su estado operativo.
- Contrato/configuración: [`docs/api/notifications.md`](../api/notifications.md).

El registro operativo conserva el email o teléfono requerido por el proveedor.
La tarea 7 implementa purga a 90 días para solicitudes terminales; ver
[`postgres-backup-restore.md`](../operations/postgres-backup-restore.md).

Verificación: PostgreSQL 16.4, Flyway V1–V20, 18 casos funcionales y suite Maven
completa con 299 tests, 0 fallos, 0 errores y 0 omitidos.

## Fase 6: Documents y Notifications

Aplicado:

- Generación de documentos A4 bajo demanda desde una venta existente.
- Descarga e impresión A4 desde la API.
- La generación usa snapshots persistidos, es de solo lectura y no almacena
  archivos PDF.
- El endpoint requiere `ORDER_CREATE` o `ADMIN_ALL` y admite ventas
  `CONFIRMED`, `DELIVERED` y `CANCELLED`.

Implementado:

- Ticket PDF de 80 mm protegido por ownership.
- Solicitudes asíncronas e idempotentes de comprobantes por email o WhatsApp.
- Webhooks externos configurables mediante variables de entorno y reintentos
  idempotentes desde outbox.
- Auditoría de solicitud y de intentos, con consulta del estado.

Pendiente de despliegue: configurar URLs y credenciales de proveedores por
entorno. La purga de solicitudes y destinatarios terminales ya está implementada
con retención predeterminada de 90 días.

Criterios:

- Confirmar una venta no genera ni almacena PDF automáticamente.
- Un fallo de email o WhatsApp no revierte la venta confirmada.
- Los reintentos de notificación son auditables.

## Fase 7: Operación y endurecimiento

Implementado:

- Workflow de CI con PostgreSQL 16 que ejecuta la suite Maven opt-in.
- Logs ECS JSON con request ID seguro en MDC; métricas HTTP de latencia y
  métricas outbox de pendientes, éxito, fallo, agotamiento y duración.
- Scripts PowerShell de backup custom cifrado con AES-256-CBC y HMAC-SHA256,
  retención local y restauración con validación HMAC. La copia a carpeta local
  OneDrive pasó SHA-256/HMAC y restauración. La tarea diaria se corrigió y quedó
  habilitada tras una corrida programada de control con código `0`; la siguiente
  ejecución está prevista para 2026-09-25 a las 03:00. Se corrigió la
  interpretación de Cloud Files: `0x00000009` significa placeholder + `InSync`;
  el usuario confirmó que ve el backup de las 05:55:56 en OneDrive. KeePassXC
  2.7.12 portable se instaló con firma/hash oficiales verificados. Se corrigió
  stdin para PowerShell 5.1 y se movió la entrada a la raíz porque el grupo
  `Recovery` no existía. La base fallida se limpió y la prueba create/add/lectura
  con datos ficticios pasó. El usuario confirmó `RESULT=OK` para la bóveda real;
  la entrada se leyó de vuelta y Cloud Files reportó `0x00000009`
  (`PLACEHOLDER` + `InSync`), confirmando la sincronización en OneDrive. La
  contraseña maestra queda bajo custodia del usuario fuera de OneDrive.
- Prueba de restauración/tamper ejecutada en una base PostgreSQL descartable.
- Procedimiento de rollback y recuperación documentado.
- ArchUnit valida que los controladores no accedan a infraestructura y que el
  dominio no dependa de las capas API, aplicación o infraestructura.
- [x] Romper la arista `shared → catalog` que cerraba `audit → shared → catalog →
  audit`, sacar los DTOs de transporte de las dependencias de aplicación y
  proteger ambas direcciones con reglas ArchUnit.
- [x] Revisar y documentar el grafo intermodular completo; resolver los ciclos
  restantes y activar la regla ArchUnit global de slices sin ciclos.
- Purga a 90 días de solicitudes terminales y eventos outbox; se conserva la
  auditoría con destinatario enmascarado.

Estado de activación y evolución:

- [x] Primera ejecución del workflow CI PostgreSQL pasó en GitHub Actions para
  `2d1decf` ([run 35957213828](https://github.com/Martinrc93/distribuidoraCore/actions/runs/35957213828), 2026-09-24).
- [x] Corregir Task Scheduler y activar la tarea diaria en el host; la ejecución
  programada de control y el restore/tamper pasaron.
- [x] Crear y verificar el escrow de la clave DPAPI en KeePassXC y comprobar la
  sincronización cloud de la bóveda. El backup de las 05:55:56 está confirmado
  en OneDrive por el usuario; su mirror local pasó HMAC y la restauración está
  verificada.
- [x] El ciclo conocido `audit → shared → catalog → audit` se rompió al eliminar
  `shared → catalog`; los servicios de aplicación ya no dependen de DTOs API.
- [x] La revisión encontró dependencias desde `shared` hacia identidad,
  documentos y pedidos. Los servicios JWT y los handlers específicos se
  movieron a sus módulos; el grafo global de producción ya pasa la regla sin
  ciclos.
- [x] Purga de solicitudes de notificación y eventos outbox terminales con
  retención predeterminada de 90 días; la auditoría enmascarada se conserva.

No quedan tareas de implementación backend abiertas en esta fase. La activación
real de webhooks requiere configurar URLs y credenciales de email/WhatsApp por
entorno, como se indica en la Fase 6.

Criterios:

- Las cuatro reglas ArchUnit de capas y la regla global de slices sin ciclos
  protegen el backend en cada suite Maven.
- Existe procedimiento de restauración probado.
- Los eventos críticos pueden rastrearse desde request hasta auditoría.

Verificación histórica de la fase (2026-09-24): suite Maven **301 tests** y 18
pruebas PostgreSQL. Verificación PostgreSQL posterior a administración y
ArchUnit (2026-09-24): **314 tests, 0 fallos, 0 errores ni omitidos**, incluidos
19 casos PostgreSQL 16.4/Flyway V1–V20 en una base descartable eliminada al
concluir. Verificación posterior a arquitectura y HTTP: **328 tests, 0 fallos,
0 errores y 19 omitidos**. Verificación de la revisión completa del grafo
(2026-09-24): **329 tests, 0 fallos, 0 errores y 19 omitidos**, con ArchUnit sin
ciclos; esta última ejecución no tenía `POSTGRES_TEST_URL`.
Verificación integrada final de seguridad y persistencia (2026-09-24): **337
tests, 0 fallos, 0 errores y 0 omitidos**, incluidos 21 casos PostgreSQL
16.4/Flyway V1–V20 y validación JPA en un cluster descartable. La matriz HTTP
recorre las seis authorities actuales; dos flujos comprueban login desde roles
persistidos, invalidación del JWT anterior y permisos del nuevo login tras
cambios de rol o permisos.

Verificación final posterior a los tres ítems de backlog (2026-09-24): **350
tests, 0 fallos, 0 errores y 0 omitidos**. Incluye 24 casos PostgreSQL 16.4,
Flyway V1–V23 y validación JPA sobre una base descartable nueva; la regla global
ArchUnit y la matriz HTTP pasaron.
