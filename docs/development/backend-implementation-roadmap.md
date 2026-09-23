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

- Estados de pedido y venta `CONFIRMED`, `DELIVERED` y `CANCELLED`, con
  `delivered_at` y `cancelled_at`.
- `orders.delivery_attempts` append-only, con número consecutivo, resultado
  `DELIVERED` o `FAILED` y observación obligatoria para fallos.
- Un intento `FAILED` conserva pedido y venta en `CONFIRMED` y permite otro
  intento.
- Un intento `DELIVERED` cambia pedido y venta juntos a `DELIVERED`; ambos son
  terminales.
- La cancelación solo opera desde `CONFIRMED`, solo la puede ejecutar un
  administrador y rechaza ventas con importe pagado.
- La cancelación revierte cada movimiento `SALE` con un movimiento
  `SALE_CANCELLATION`, crea un `CREDIT` por la deuda pendiente y disminuye el
  saldo del cliente en la misma transacción. Pedido y venta pasan a
  `CANCELLED`.

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

`delivery-attempts` requiere `ORDER_CREATE` o `ADMIN_ALL`; `cancel` requiere
`ADMIN_ALL`. El request de intento valida `result` no vacío, limitado a 20
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
- Un `DELIVERED` actualiza pedido y venta sin modificar stock, pagos ni ledger.
- Cancelar revierte movimientos `SALE`, agrega `SALE_CANCELLATION`, registra el
  `CREDIT` de cuenta corriente y devuelve el balance del cliente al valor previo.
- Reintentar después de `DELIVERED` o `CANCELLED` es rechazado y no agrega
  movimientos, intentos, créditos ni cambios de balance.
- El smoke reproducible de PostgreSQL verifica estos invariantes sobre el
  volumen persistente de Compose.

## Fase 6: Documents y Notifications

Aplicado:

- Generación de documentos A4 bajo demanda desde una venta existente.
- Descarga e impresión A4 desde la API.
- La generación usa snapshots persistidos, es de solo lectura y no almacena
  archivos PDF.
- El endpoint requiere `ORDER_CREATE` o `ADMIN_ALL` y admite ventas
  `CONFIRMED`, `DELIVERED` y `CANCELLED`.

Pendiente explícitamente:

- Tickets y otros formatos de comprobante.
- Envío explícito por WhatsApp mediante link o proveedor configurable.
- Envío por email.
- Outbox para efectos secundarios sin ocultar invariantes transaccionales.

Criterios:

- Confirmar una venta no genera ni almacena PDF automáticamente.
- Un fallo de email o WhatsApp no revierte la venta confirmada.
- Los reintentos de notificación son auditables.

## Fase 7: Operación y endurecimiento

Implementar:

- Backups PostgreSQL diarios, retención definida y verificación de tamaño.
- Prueba de restauración automatizada.
- Logs estructurados con correlation ID.
- Métricas básicas de latencia, errores y disponibilidad.
- Tests arquitectónicos ArchUnit o Spring Modulith.

Criterios:

- Las dependencias entre módulos violatorias fallan en CI.
- Existe procedimiento de restauración probado.
- Los eventos críticos pueden rastrearse desde request hasta auditoría.
