# Roadmap de implementación del backend

Este roadmap aplica los ADR de forma incremental. Cada fase debe dejar una
vertical ejecutable, con migraciones Flyway, tests de aplicación y endpoints
documentados antes de comenzar la siguiente.

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

Aplicados parcialmente:

- ADR-004: usuario local, Argon2id, JWT stateless de 15 minutos y permisos en
  claims.
- ADR-009: auditoría de login con correlation ID.

Pendiente en esta fase:

- Alta administrativa de usuarios `INVITED`.
- Token de activación de un solo uso con expiración de 30 minutos.
- Refresh token rotativo, revocable y almacenado como hash.
- Bloqueo/desbloqueo administrativo y revocación de sesiones.
- Permisos persistidos y autorización con `@PreAuthorize`.

Criterios:

- Login correcto devuelve Bearer token.
- Password inválido no revela si el email existe.
- Tres intentos inválidos bloquean al usuario.
- Login exitoso y fallido generan eventos append-only.
- Endpoints privados rechazan requests sin token válido.

## Fase 2: Seller y Customer

Implementar:

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

Pendiente explícitamente:

- Historial de precios y vigencias futuras.
- Descuentos y reglas de precio históricos fuera de la confirmación.

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

Aplicados parcialmente:

- `inventory.inventory_balances` con saldo actual.
- `inventory.stock_movements` append-only.
- Ajuste manual de stock mediante delta firmado.
- Lock pesimista del balance dentro de la transacción.
- Auditoría del ajuste manual con operación `STOCK_ADJUSTMENT`.

Pendiente explícitamente:

- Delta de stock por cancelación y edición.

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

Pendiente explícitamente:

- Validaciones de permisos de vendedor específicas del pedido.

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

Pendiente: configurar URLs y credenciales de proveedores por entorno, y definir
retención/purga de destinatarios.

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
  retención local, registro de tarea diaria y restauración con validación HMAC.
- Prueba de restauración/tamper ejecutada en una base PostgreSQL descartable.
- Procedimiento de rollback y recuperación documentado.
- Purga a 90 días de solicitudes terminales y eventos outbox; se conserva la
  auditoría con destinatario enmascarado.

Pendiente de activación o evolución:

- Confirmar primera ejecución del workflow en GitHub Actions.
- Registrar/activar la tarea diaria en el host operativo y configurar réplica
  externa de backups cifrados.
- Incorporar ArchUnit o Spring Modulith.

Criterios:

- Las dependencias entre módulos violatorias fallan en CI.
- Existe procedimiento de restauración probado.
- Los eventos críticos pueden rastrearse desde request hasta auditoría.

Verificación de cierre (2026-09-24): suite Maven completa **301 tests, 0
fallos, 0 errores y 0 omitidos**; PostgreSQL 16.4, Flyway V1–V20 y 18 pruebas
funcionales. La restauración/tamper se verificó por separado en una base
descartable, que se eliminó al concluir.
