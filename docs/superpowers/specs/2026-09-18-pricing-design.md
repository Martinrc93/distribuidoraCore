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

No incluye todavía:

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

### `customer.customers`

Agregar `price_list_id UUID NULL REFERENCES catalog.price_lists`. La relación es opcional; quitarla activa el fallback a `GENERAL`.

## Datos iniciales

La migración crea `GENERAL`, `LISTA_2` y `LISTA_3`. Cada producto existente recibe inicialmente su valor actual de `catalog.products.price` en las tres listas. `GENERAL` queda activa y marcada como default; las otras dos quedan activas y no default.

## Reglas de negocio

- Solo `ADMIN_ALL` puede crear, modificar o desactivar listas y precios.
- Una lista desactivada no puede asignarse a clientes ni seleccionarse explícitamente para pedidos nuevos.
- No se eliminan físicamente listas ni precios.
- No puede haber más de diez listas, contando activas e inactivas.
- No puede haber más de una lista default.
- La lista default debe ser `GENERAL` en esta fase.
- Un precio ausente en una lista no hace fallback a otra lista; la resolución devuelve `404`.
- Los cambios de precio tienen efecto inmediato.

## API

### Listas

- `GET /api/pricing/lists?page=0&size=20`: consulta autenticada.
- `POST /api/pricing/lists`: crea una lista y devuelve `201`.
- `PUT /api/pricing/lists/{id}`: renombra una lista y devuelve `204`.
- `PATCH /api/pricing/lists/{id}/status`: activa o desactiva y devuelve `204`.

### Precios

- `GET /api/pricing/lists/{listId}/prices?page=0&size=20`: lista precios.
- `PUT /api/pricing/lists/{listId}/products/{productId}`: crea o actualiza precio y devuelve `204`.

### Asignación a clientes

- `PATCH /api/customers/{customerId}/price-list`: asigna una lista activa o `null` y devuelve `204`.

### Resolución

- `GET /api/pricing/resolve?customerId={id}&productId={id}`: usa lista del cliente o `GENERAL`.
- `GET /api/pricing/resolve?customerId={id}&productId={id}&priceListId={id}`: usa la lista explícita.

La respuesta de resolución incluye `priceListId`, `priceListCode`, `productId` y `unitPrice`.

## Errores

- `400 INVALID_REQUEST`: nombre/código/precio inválido, estado inválido o payload incompleto.
- `401 UNAUTHORIZED`: JWT ausente o inválido.
- `403 FORBIDDEN`: mutación sin `ADMIN_ALL`.
- `404 NOT_FOUND`: lista, producto, cliente o precio inexistente.
- `409 CONFLICT`: límite de diez listas, código duplicado o desactivación de `GENERAL`.

## Auditoría

Las operaciones `PRICELIST_CREATE`, `PRICELIST_UPDATE`, `PRICELIST_STATUS`, `PRODUCT_PRICE_UPDATE` y `CUSTOMER_PRICE_LIST_ASSIGNMENT` se registran mediante `AuditService` con actor, recurso y valores relevantes.

## Pruebas

- Migración V5 sobre una base con productos y clientes existentes.
- Seed inicial de tres listas y precios para cada producto.
- Unicidad y límite de diez listas.
- Resolución por lista del cliente, fallback `GENERAL` y lista explícita.
- Rechazo de lista inactiva y precio inexistente.
- Permiso `ADMIN_ALL`, respuestas HTTP y auditoría.
- Verificación de que cambiar un precio no modifica todavía pedidos históricos.
