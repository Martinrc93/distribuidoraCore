# Diseño: Pricing normalizado

## Objetivo

Implementar listas de precios normalizadas, precios por producto, asignación opcional a clientes y resolución determinística de precios para que la futura confirmación de pedidos pueda guardar snapshots sin duplicar reglas.

## Alcance

Incluye:

- Tres listas iniciales: `GENERAL`, `LISTA_2` y `LISTA_3`.
- Creación, renombrado, activación y desactivación administrativa.
- Máximo de diez listas totales, incluyendo activas e inactivas.
- Precios por lista y producto con `NUMERIC(19,4)`.
- Asignación opcional de una lista a cada cliente.
- Resolución por cliente, producto y lista explícita.
- Fallback a `GENERAL` cuando el cliente no tiene lista asignada.
- Auditoría de mutaciones.

La decisión inicial de no incluir vigencias ni descuentos persistidos quedó
reemplazada el 2026-09-24: V21 incorpora historial/vigencias y V22 reglas
comerciales `LINE`/`ORDER`. El contrato vigente está en
[`docs/api/pricing.md`](../../api/pricing.md).

El alcance original no incluía:

- Vigencias futuras o historial bitemporal de precios.
- Descuentos comerciales.
- Snapshots dentro de pedidos o ventas.
- Modificación automática de pedidos existentes.

## Modelo de datos

La migración V5 agregará:

### `catalog.price_lists`

- `id UUID PRIMARY KEY`.
- `code VARCHAR(40) UNIQUE NOT NULL`.
- `name VARCHAR(120) NOT NULL`.
- `status VARCHAR(20) NOT NULL`, con `ACTIVE` e `INACTIVE`.
- `is_default BOOLEAN NOT NULL DEFAULT FALSE`.
- `created_at` y `updated_at`.

Debe existir exactamente una lista general activa. La lista `GENERAL` no puede desactivarse mientras sea la lista por defecto.

### `catalog.product_prices`

- `price_list_id UUID REFERENCES catalog.price_lists`.
- `product_id UUID REFERENCES catalog.products`.
- `price NUMERIC(19,4) NOT NULL`.
- `created_at` y `updated_at`.
- Clave primaria compuesta `(price_list_id, product_id)`.
- `price >= 0`.

### `catalog.product_price_history` (vigente desde V21)

- `id UUID PRIMARY KEY`, precio y sus timestamps de registro.
- `price_list_id` y `product_id` referencian sus entidades de catálogo.
- `effective_on DATE` determina desde qué fecha comercial aplica el precio.
- La migración crea una versión inicial por precio existente, fechada según
  `America/Argentina/Buenos_Aires`; no reconstruye historial anterior a V21.
- Las lecturas comerciales resuelven la versión efectiva más reciente. Los
  cambios futuros no requieren una tarea programada para activarse.
- La tabla V5 `product_prices` permanece por compatibilidad, no es la fuente de
  verdad para lecturas actuales ni futuras.

### `customer.customers`

Agregar `price_list_id UUID NULL REFERENCES catalog.price_lists`. La relación es opcional; quitarla activa el fallback a `GENERAL`.

## Datos iniciales

La migración crea diez listas con identificadores deterministas. Cada producto
existente recibe inicialmente su valor actual de `catalog.products.price` en
`GENERAL`, `LISTA_2` y `LISTA_3`. `GENERAL` queda activa y marcada como default;
las otras dos quedan activas y no default; `LISTA_4` a `LISTA_10` quedan
inactivas.

## Reglas de negocio

- Solo `ADMIN_ALL` puede crear, modificar o desactivar listas y precios.
- Una lista desactivada no puede asignarse a clientes ni seleccionarse explícitamente para pedidos nuevos.
- No se eliminan físicamente listas ni precios.
- No puede haber más de diez listas, contando activas e inactivas.
- No puede haber más de una lista default.
- La lista default debe ser `GENERAL` en esta fase.
- Un precio ausente prueba las listas activas anteriores por identificador; si
  no encuentra precio, la resolución devuelve `404`.
- Solo `ADMIN_ALL` puede registrar o cancelar precios.
- Si no se envía fecha, el cambio rige la fecha actual de Buenos Aires. Las
  fechas pasadas se rechazan; modificar una programación futura reemplaza el
  valor de esa fecha.
- Precio actual y futuro no pueden quedar debajo del costo del producto.
- Al subir el costo, las programaciones activas incompatibles deben ajustarse o
  cancelarse antes.
- Los snapshots de pedidos/ventas existentes no cambian por una nueva vigencia.

## API

### Listas

- `GET /api/pricing/lists?page=0&size=20`: consulta autenticada.
- `POST /api/pricing/lists`: crea una lista y devuelve `201`.
- `PUT /api/pricing/lists/{id}`: renombra una lista y devuelve `204`.
- `PATCH /api/pricing/lists/{id}/status`: activa o desactiva y devuelve `204`.

### Precios

- `GET /api/pricing/lists/{listId}/prices?page=0&size=20`: lista precios.
- `PUT /api/pricing/lists/{listId}/products/{productId}`: crea/actualiza un
  precio actual o futuro y devuelve `204`.
- `GET /api/pricing/lists/{listId}/products/{productId}/history`: consulta
  historial y programaciones futuras.
- `DELETE /api/pricing/lists/{listId}/products/{productId}/history/{effectiveOn}`:
  cancela una programación futura y devuelve `204`.

### Asignación a clientes

- `PATCH /api/customers/{customerId}/price-list`: asigna una lista activa o `null` y devuelve `204`.

### Resolución

- `GET /api/pricing/resolve?customerId={id}&productId={id}`: usa lista del cliente o `GENERAL`.
- `GET /api/pricing/resolve?customerId={id}&productId={id}&priceListId={id}`: usa la lista explícita.
- `asOf=YYYY-MM-DD` opcional consulta el precio aplicable en una fecha histórica
  o futura.

La respuesta de resolución incluye `priceListId`, `priceListCode`, `productId` y `unitPrice`.

## Errores

- `400 INVALID_REQUEST`: nombre/código/precio inválido, estado inválido o payload incompleto.
- `401 UNAUTHORIZED`: JWT ausente o inválido.
- `403 FORBIDDEN`: mutación sin `ADMIN_ALL`.
- `404 NOT_FOUND`: lista, producto, cliente o precio inexistente en la lista
  seleccionada y sus listas activas anteriores.
- `409 CONFLICT`: límite de diez listas, código duplicado o desactivación de `GENERAL`.

## Auditoría

Las operaciones `PRICELIST_CREATE`, `PRICELIST_UPDATE`, `PRICELIST_STATUS`, `PRODUCT_PRICE_UPDATE` y `CUSTOMER_PRICE_LIST_ASSIGNMENT` se registran mediante `AuditService` con actor, recurso y valores relevantes.

## Pruebas

- Migración V5 sobre una base con productos y clientes existentes.
- Seed inicial de diez listas; las tres primeras activas reciben precios para
  cada producto.
- Unicidad y límite de diez listas.
- Resolución por lista del cliente, fallback `GENERAL` y lista explícita.
- Rechazo de lista inactiva y fallback a listas activas anteriores cuando falta
  un precio.
- Permiso `ADMIN_ALL`, respuestas HTTP y auditoría.
- Verificación de que cambiar un precio no modifica todavía pedidos históricos.
- Crear, consultar, resolver y cancelar una vigencia futura sobre PostgreSQL;
  rechazar fecha pasada y precio inferior al costo.
