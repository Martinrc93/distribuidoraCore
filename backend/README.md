# Distribuidora Backend

Backend REST en Spring Boot para una instancia empresarial.

## Requisitos

- Java 21 LTS.
- Maven 3.9+.
- PostgreSQL 16+ para ejecución local.

## Ejecutar tests

```bash
mvn test
```

El test de contexto usa H2 únicamente para validar el arranque. Los tests de
persistencia deben utilizar PostgreSQL mediante Testcontainers.

## Ejecutar la aplicación

Configurar:

```text
DB_URL=jdbc:postgresql://localhost:5432/distribuidora
DB_USERNAME=distribuidora
DB_PASSWORD=distribuidora
```

Luego ejecutar:

```bash
mvn spring-boot:run
```

## Ejecutar todo con Docker Compose

Desde la raíz del repositorio:

```powershell
docker compose up --build
```

Servicios:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`
- Health: `http://localhost:8080/actuator/health`
- Swagger: `http://localhost:8080/swagger-ui.html`
- PostgreSQL: `localhost:5432`

El backend recibe `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` y
`JWT_ISSUER` desde Compose. Para crear un administrador local inicial sin
guardar credenciales en el repositorio, definir también `ADMIN_EMAIL` y
`ADMIN_PASSWORD`. Flyway aplica las migraciones al arrancar y PostgreSQL usa
el volumen `postgres-data`.

Endpoints iniciales:

```text
GET /actuator/health
GET /v3/api-docs
GET /swagger-ui.html
```

## Inventario

Los endpoints de inventario requieren un JWT. Los ajustes requieren además la
autoridad `STOCK_ADJUST`; el token de un administrador la incluye.

Login de ejemplo:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin1@distribuidora.local","password":"ChangeMe123!"}'
```

La respuesta `200` tiene la forma:

```json
{"accessToken":"<jwt>","tokenType":"Bearer","expiresInSeconds":900}
```

Reemplace `<jwt>` y `<productId>` en los siguientes ejemplos.

Consultar saldos paginados:

```bash
curl 'http://localhost:8080/api/inventory?page=0&size=20' \
  -H 'Authorization: Bearer <jwt>'
```

Ajuste positivo, que suma `2.5` unidades:

```bash
curl -i -X POST \
  http://localhost:8080/api/inventory/<productId>/adjustments \
  -H 'Authorization: Bearer <jwt>' \
  -H 'Content-Type: application/json' \
  -d '{"quantity":2.5,"reason":"Recepción de mercadería"}'
```

Respuesta esperada: `204 No Content`.

Ajuste negativo, que resta `1.5` unidades:

```bash
curl -i -X POST \
  http://localhost:8080/api/inventory/<productId>/adjustments \
  -H 'Authorization: Bearer <jwt>' \
  -H 'Content-Type: application/json' \
  -d '{"quantity":-1.5,"reason":"Merma"}'
```

Respuesta esperada: `204 No Content`. El stock negativo está permitido y las
cantidades deben ser múltiplos de `0.5`.

Historial paginado del producto:

```bash
curl 'http://localhost:8080/api/inventory/<productId>/movements?page=0&size=20' \
  -H 'Authorization: Bearer <jwt>'
```

La respuesta `200` contiene `content` con `movementType`, `quantity`, `reason`,
`referenceType`, `referenceId` y `date`, además de los metadatos de paginación.

Errores esperados:

- `400 Bad Request` para un delta `0`, una cantidad que no sea múltiplo de
  `0.5`, un motivo vacío o un motivo de más de `500` caracteres.
- `403 Forbidden` para un JWT válido sin autoridad `STOCK_ADJUST` al ejecutar
  un ajuste.
- `401 Unauthorized` cuando falta el JWT o no es válido.

## Pricing

El módulo de pricing usa listas normalizadas y precios por producto. La
migración crea tres listas activas: `GENERAL`, `LISTA_2` y `LISTA_3`. Se pueden
crear hasta diez listas en total; las listas y sus precios no se eliminan
físicamente. `GENERAL` es la lista default y fallback para clientes sin lista
asignada. Los cambios de precio tienen efecto inmediato.

Lecturas autenticadas:

```text
GET /api/pricing/lists?page=0&size=20
GET /api/pricing/lists/{listId}/prices?page=0&size=20
GET /api/pricing/resolve?customerId={customerId}&productId={productId}
GET /api/pricing/resolve?customerId={customerId}&productId={productId}&priceListId={priceListId}
```

La resolución usa `priceListId` explícito cuando se informa; si no, usa la
lista asignada al cliente y, en último lugar, `GENERAL`. La respuesta contiene
`priceListId`, `priceListCode`, `productId` y `unitPrice`. Una lista inactiva o
un precio inexistente no se sustituye por otra lista.

Mutaciones exclusivas de `ADMIN_ALL`:

```text
POST /api/pricing/lists
PUT /api/pricing/lists/{listId}
PATCH /api/pricing/lists/{listId}/status
PUT /api/pricing/lists/{listId}/products/{productId}
PATCH /api/customers/{customerId}/price-list
```

### Contratos de escritura y resolución

Los cuerpos JSON de las mutaciones son concretos:

```json
POST /api/pricing/lists
{ "code": "CUSTOM", "name": "Clientes" }

PUT /api/pricing/lists/<listId>
{ "name": "Clientes mayoristas" }

PATCH /api/pricing/lists/<listId>/status
{ "status": "ACTIVE" }

PUT /api/pricing/lists/<listId>/products/<productId>
{ "price": 10.2500 }

PATCH /api/customers/<customerId>/price-list
{ "priceListId": "<priceListId>" }
```

Para quitar la lista asignada y volver al fallback se envía
`{ "priceListId": null }`. Los códigos de lista tienen como máximo 40
caracteres; los nombres, 120; `status` solo acepta `ACTIVE` o `INACTIVE`; y
`price` es obligatorio, no negativo y admite hasta cuatro decimales.

Respuestas exitosas:

- `POST /api/pricing/lists`: `201 Created`, con `{ "id": "<uuid>" }`.
- Las dos actualizaciones de lista/precio y la asignación de cliente:
  `204 No Content`.
- Las lecturas de listas, precios y resolución: `200 OK`. La resolución
  devuelve `{ "priceListId": "<uuid>", "priceListCode": "GENERAL",
  "productId": "<uuid>", "unitPrice": 10.2500 }`.

Errores de Pricing:

- `400 Bad Request` (`code: INVALID_REQUEST`) para JSON ausente o mal formado,
  UUID inválidos, campos obligatorios ausentes, nombres/códigos demasiado
  largos, `status` distinto de `ACTIVE`/`INACTIVE`, precios negativos o con
  más de cuatro decimales, y parámetros de resolución inválidos.
- `403 Forbidden` (`code: FORBIDDEN`) para un JWT válido sin `ADMIN_ALL` al
  ejecutar cualquiera de las cinco mutaciones.
- `404 Not Found` (`code: NOT_FOUND`) cuando no existe la lista, cliente o
  producto solicitado, y cuando no existe el precio del producto en la lista
  seleccionada. Un precio ausente no hace fallback a otra lista.
- `409 Conflict` (`code: CONFLICT`) al repetir un código de lista, crear una
  undécima lista, cambiar un precio en una lista inactiva, asignar una lista
  inactiva, desactivar `GENERAL` o resolver mediante una lista inactiva.

La resolución selecciona, en este orden, `priceListId` explícito, la lista
asignada al cliente o la lista activa `GENERAL`. Una lista inactiva explícita o
asignada es un conflicto (`409`), no un fallback.

## Confirmación de pedidos

La confirmación es atómica y requiere un JWT con `ORDER_CREATE`:

```text
POST /api/orders/confirm
GET  /api/orders/{orderId}
GET  /api/orders/by-number/{orderNumber}
```

Request de confirmación:

```json
{
  "idempotencyKey": "checkout-2026-0001",
  "customerId": "<customer-uuid>",
  "priceListId": "<optional-price-list-uuid>",
  "lines": [{
    "productId": "<product-uuid>",
    "quantity": 2.0,
    "lineDiscountPercent": 10.0000,
    "unitPriceOverride": null
  }],
  "orderDiscountPercent": 0.0000,
  "payments": [{"method": "CASH", "amount": 18.0000}]
}
```

`payments` puede combinar `CASH`, `BANK_TRANSFER` y `CUSTOMER_ACCOUNT`. Si se
omite o está vacío, el total completo genera un débito en cuenta corriente.
`CASH` y `BANK_TRANSFER` son el pago monetario y determinan `sale.paid`; cada
entrada `CUSTOMER_ACCOUNT` genera su propio débito `DEBIT` en el ledger. Si el
total menos los pagos monetarios es mayor que la suma de esos débitos explícitos,
la diferencia se agrega como un débito de remanente. Por lo tanto, la suma de
débitos del ledger, el aumento del saldo del cliente y el balance de la venta
son siempre `total - pagos monetarios`. La suma de todos los entries de pago no
puede superar el total.
La respuesta `201` contiene `orderId`, `saleId`, números, `total`, `paid` y
`balance`. Cada línea conserva nombre de producto, cantidad, precio unitario,
lista de precios, descuento y total aplicado.

El mismo `idempotencyKey` con el mismo payload devuelve la respuesta original y
no crea filas adicionales. Reutilizarlo con otro payload devuelve `409` con
`code: IDEMPOTENCY_CONFLICT`. Otros errores son `400 INVALID_REQUEST`,
`401` sin JWT, `403 FORBIDDEN` sin permiso o al usar overrides sin `ADMIN_ALL`,
`404 NOT_FOUND` para cliente/producto/precio/pedido inexistente y `409 CONFLICT`
para reglas de negocio o pagos inválidos. Un fallo de stock o persistencia
revierte pedido, venta, pagos, cuenta corriente y movimiento de stock.

### V7: entrega y cancelación

El ciclo de vida de pedido y venta usa `CONFIRMED`, `DELIVERED` y `CANCELLED`.
La migración `V7__add_delivery_cancellation_support.sql` agrega timestamps y
la tabla append-only `orders.delivery_attempts`.

Registrar un intento requiere `ORDER_CREATE` o `ADMIN_ALL`:

```text
POST /api/orders/{orderId}/delivery-attempts
```

```json
{ "result": "FAILED", "observation": "Dirección cerrada" }
```

`result` solo admite `FAILED` o `DELIVERED`, tiene un máximo de 20 caracteres y
`observation` tiene un máximo de 2000. Los intentos `FAILED` requieren una
observación no vacía y dejan pedido y venta en `CONFIRMED`, por lo que se puede
volver a intentar. Un intento `DELIVERED` persiste el intento y actualiza pedido
y venta juntos a `DELIVERED`; no cambia stock, pagos ni ledger.

La respuesta exitosa de ambos intentos es `204 No Content`. Cancelar requiere
`ADMIN_ALL`:

```text
POST /api/orders/{orderId}/cancel
-> 204 No Content
```

La cancelación solo acepta un pedido y venta `CONFIRMED` y rechaza una venta
con pagos (`409 CONFLICT`). En una única transacción:

- Cada movimiento `SALE` se revierte con un movimiento positivo
  `SALE_CANCELLATION`, usando el pedido como referencia canónica.
- La deuda pendiente (`sale.total - sale.paid`) genera un `CREDIT` en
  `customer.account_ledger` y reduce `customer.customers.balance` por el mismo
  importe.
- Pedido y venta pasan a `CANCELLED` y se registra `cancelled_at`.

`DELIVERED` y `CANCELLED` son terminales. Los intentos o cancelaciones sobre
estados terminales devuelven `409 CONFLICT` sin agregar movimientos, ledger ni
cambios de balance.

Errores V7:

- `400 INVALID_REQUEST`: resultado inválido, observación de fallo ausente,
  JSON malformado o UUID inválido.
- `401 Unauthorized`: falta el JWT o no es válido.
- `403 FORBIDDEN`: falta `ORDER_CREATE`/`ADMIN_ALL` para intentos o
  `ADMIN_ALL` para cancelación.
- `404 NOT_FOUND`: no existe el pedido solicitado.
- `409 CONFLICT`: estado no modificable, venta pagada o reintento de una
  operación terminal.

## Documentos A4 de venta

El comprobante A4 se genera bajo demanda a partir de los snapshots persistidos
de la venta. La generación es estrictamente de lectura: no crea movimientos de
inventario, pagos ni asientos de cuenta corriente, y no modifica saldos,
estados ni otros datos. No se almacenan archivos PDF.

El endpoint requiere un JWT con `ORDER_CREATE` o `ADMIN_ALL`:

```text
GET /api/orders/{orderId}/documents/a4
```

La respuesta exitosa es `200 OK` con `Content-Type: application/pdf`,
`Content-Length` y `Content-Disposition: attachment; filename="venta-<number>.pdf"`.
Se admiten ventas en estado `CONFIRMED`, `DELIVERED` o `CANCELLED`. La ausencia
del pedido responde `404 NOT_FOUND`; un pedido sin venta responde `409 CONFLICT`.
Las solicitudes sin autenticación responden `401` y sin uno de los permisos
requeridos, `403 FORBIDDEN`.

### Smoke reproducible con PostgreSQL de Compose

Con Docker disponible, ejecutar desde la raíz sin borrar el volumen persistente:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\order-confirmation-smoke.ps1
```

El script ejecuta `docker compose up -d --build db backend frontend` y luego
reinicia `backend` y `frontend` (no ejecuta `down` ni elimina `postgres-data`),
espera el backend saludable, obtiene un JWT de
`admin1@distribuidora.local` y selecciona un cliente/producto demo. Verifica la
confirmación concurrente/idempotente y el rollback posterior a escritura.
También crea órdenes aisladas para probar que dos intentos `FAILED` conservan
`CONFIRMED`, que `DELIVERED` actualiza pedido y venta sin cambiar stock, pagos ni
ledger, y que cancelar revierte movimientos `SALE`, agrega
`SALE_CANCELLATION`, registra el `CREDIT` y devuelve el balance. Comprueba que
los reintentos sobre `DELIVERED` o `CANCELLED` responden `409` sin efectos
adicionales, y vuelve a consultar la orden cancelada después del reinicio para
probar la persistencia. El volumen persistente `postgres-data` nunca se elimina.

Si se cambiaron las credenciales de Compose, usar variables de entorno antes del
comando: `$env:ADMIN_EMAIL`, `$env:ADMIN_PASSWORD`, `$env:POSTGRES_DB` y
`$env:POSTGRES_USER`.

Detalle de pedido (`200`):

```json
{
  "order": {"id":"<uuid>","number":"ORD-...","status":"CONFIRMED","subtotal":20.0000,"discount":2.0000,"total":18.0000,"customerBalance":8.0000},
  "items": [{"productId":"<uuid>","productName":"Producto","quantity":2.0000,"unitPrice":10.0000,"lineTotal":18.0000,"priceListId":"<uuid>","priceListCode":"GENERAL","lineDiscountPercent":10.0000}],
  "sale": {"id":"<uuid>","number":"SAL-...","status":"CONFIRMED","total":18.0000,"paid":10.0000,"balance":8.0000},
  "payments": [{"amount":10.0000,"method":"CASH"}],
  "account": {"debit":8.0000,"credit":0.0000,"net":8.0000}
}
```

Todavía no se implementan historial de precios, vigencias futuras, descuentos
ni documentos. Los snapshots de precio y descuento ya se persisten al confirmar
pedidos.

El plan incremental de aplicación de los ADR está en
`docs/development/backend-implementation-roadmap.md`.
