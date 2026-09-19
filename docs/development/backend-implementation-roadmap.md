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
- Tres listas iniciales activas: `GENERAL`, `LISTA_2` y `LISTA_3`.
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
- Un precio ausente no hace fallback a otra lista y devuelve `404`.
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
para un precio ausente en la lista seleccionada; y `409 CONFLICT` para código
duplicado, límite total de diez listas, lista inactiva al asignar o cambiar
precios, desactivación de la lista default `GENERAL` o resolución con una lista
inactiva. Un precio faltante o una lista inactiva nunca se reemplazan mediante
fallback.

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

- Estados `DELIVERED` y `CANCELLED`.
- Cancelación, edición y reversión de stock.
- Validaciones de permisos de vendedor específicas del pedido.

Criterios:

- No se persisten borradores.
- Confirmar es atómico: si falla stock, precio o pago, no se guarda la venta.
- Reintentar con la misma clave y payload no duplica filas y devuelve la respuesta original.

## Fase 6: Documents y Notifications

Implementar:

- Generación de PDF bajo demanda.
- Descarga e impresión desde la API.
- Envío explícito por WhatsApp mediante link o proveedor configurable.
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
