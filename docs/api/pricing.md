# Precios y vigencias

Todos los endpoints están bajo `/api/pricing`. Las consultas requieren un
usuario autenticado. Las modificaciones requieren la autoridad `ADMIN_ALL` y
se registran en auditoría.

## Regla de vigencia

`catalog.product_price_history` es la fuente de verdad. Para la fecha comercial
actual se usa la última versión cuyo `effectiveOn` no supera la fecha de
`America/Argentina/Buenos_Aires`. Un precio futuro entra en vigor por fecha, sin
un job que tenga que activarlo. Los pedidos y ventas confirmados conservan sus
snapshots y no se recalculan retroactivamente.

La migración V21 establece una versión inicial con fecha de Buenos Aires a
partir del precio que existía al desplegarla. No reconstruye cambios anteriores
a V21. La tabla `catalog.product_prices` se conserva para compatibilidad con
escrituras inmediatas antiguas; las lecturas de negocio usan el historial.

## Consultar listas y precios actuales

- `GET /api/pricing/lists?page=0&size=20`
- `GET /api/pricing/lists/{listId}/prices?page=0&size=20`

La lista de precios devuelve, por producto, el precio efectivo hoy y el campo
`effectiveOn` que lo estableció.

## Cambiar o programar un precio

`PUT /api/pricing/lists/{listId}/products/{productId}` devuelve `204`.

```json
{
  "price": 1250.5000,
  "effectiveOn": "2026-10-01"
}
```

`effectiveOn` es opcional. Si se omite, el cambio rige hoy. Si es una fecha
futura, el precio se programa y no afecta la resolución actual. Editar la misma
lista, producto y fecha futura reemplaza el valor pendiente. Las fechas pasadas
se rechazan. El precio no puede ser negativo, superar `NUMERIC(19,4)` ni quedar
por debajo del costo vigente del producto.

## Consultar historial

`GET /api/pricing/lists/{listId}/products/{productId}/history?page=0&size=20`

Devuelve las versiones con `effectiveOn`, `recordedAt`, `updatedAt` y `scheduled`.
La paginación limita el tamaño a 100 filas.

## Cancelar un precio programado

`DELETE /api/pricing/lists/{listId}/products/{productId}/history/{effectiveOn}`
devuelve `204`. Solo elimina una entrada cuya fecha siga siendo futura. Una
fecha actual/pasada o una entrada inexistente produce un error; la cancelación
queda auditada como `PRODUCT_PRICE_SCHEDULE_CANCEL`.

## Resolver por cliente o fecha

- `GET /api/pricing/resolve?customerId={id}&productId={id}`: lista del cliente
  o `GENERAL`.
- `GET /api/pricing/resolve?customerId={id}&productId={id}&priceListId={id}`:
  lista explícita.
- Se puede agregar `asOf=2026-10-01` para consultar una fecha histórica o
  futura sin cambiar datos.

La respuesta incluye `priceListId`, `priceListCode`, `productId`, `unitPrice` y
`effectiveOn`. Si la lista seleccionada no tiene versión aplicable, se busca
una lista activa anterior según el orden de identificadores ya definido; si no
se encuentra ningún precio, se responde `404`.

## Errores

- `400 INVALID_REQUEST`: precio inválido, fecha pasada o precio inferior al
  costo.
- `401 UNAUTHORIZED`: sesión ausente o inválida.
- `403 FORBIDDEN`: la mutación no tiene `ADMIN_ALL`.
- `404 NOT_FOUND`: lista, cliente, producto o precio aplicable inexistente.
- `409 CONFLICT`: regla de listas o vigencia comercial incompatible.

Al aumentar el costo del producto, el backend bloquea el producto y rechaza el
cambio si alguna vigencia futura activa queda por debajo del nuevo costo; se
debe ajustar o cancelar primero esa vigencia.

## Reglas de descuento comerciales

Las reglas se administran desde:

- `GET /api/pricing/discount-rules?page=0&size=20`
- `POST /api/pricing/discount-rules` devuelve `201` y el ID creado.
- `PUT /api/pricing/discount-rules/{id}` actualiza una regla.
- `PATCH /api/pricing/discount-rules/{id}/status` activa o desactiva la regla.

Las consultas requieren autenticación; las mutaciones requieren `ADMIN_ALL` y
se auditan como `DISCOUNT_RULE_CREATE`, `DISCOUNT_RULE_UPDATE` y
`DISCOUNT_RULE_STATUS`. No hay borrado físico.

```json
{
  "code": "CLIENTE_LINEA10",
  "description": "10% para este cliente y producto",
  "kind": "LINE",
  "percent": 10.0000,
  "customerId": "<uuid cliente>",
  "priceListId": null,
  "productId": "<uuid producto>",
  "validFrom": "2026-09-24",
  "validUntil": null,
  "priority": 0
}
```

`kind` acepta `LINE` u `ORDER`. Una regla `LINE` requiere `productId`; una
regla `ORDER` no admite producto. `customerId` y `priceListId` son opcionales;
si no se informan, la regla aplica a todos los clientes o listas dentro de su
alcance. `validFrom` es inclusiva y, si se omite, toma la fecha comercial de
Buenos Aires; `validUntil` también es inclusiva. `priority` va de -1000 a 1000
y por defecto es 0.

Al confirmar o editar un pedido, se elige como máximo una regla por línea y una
regla de orden. Gana la mayor prioridad; a igual prioridad gana el alcance más
específico (cliente y lista), seguido por la regla más nueva y su ID para
desempatar. La regla de línea compara producto, cliente y la lista efectiva
resuelta para ese producto. La regla de orden usa lista explícita, lista del
cliente o `GENERAL`, en ese orden.

Las reglas elegidas se aplican automáticamente cuando el request envía
`lineDiscountPercent`/`orderDiscountPercent` en cero. Un porcentaje manual
mayor que cero sigue siendo un override administrativo y requiere `ADMIN_ALL`;
reemplaza la regla automática del mismo nivel. El descuento de línea se aplica
al importe bruto de cada línea y el descuento de orden al subtotal restante.
No se apilan varias reglas del mismo nivel.

El porcentaje efectivo y el ID de regla se guardan en los snapshots de
`orders.orders`, `orders.order_items`, `sale.sales` y `sale.sale_items`. Cambiar
o desactivar una regla no modifica pedidos ni ventas ya confirmados.
