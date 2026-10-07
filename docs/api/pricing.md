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

## Descuentos manuales

Solo se aplican los porcentajes explícitos de línea y pedido, con autorización
`ADMIN_ALL`. Un porcentaje cero conserva el precio completo; no existen reglas
comerciales automáticas ni endpoints para administrarlas.

La migración V32 elimina las reglas guardadas y sus referencias en pedidos y
ventas. Conserva los porcentajes e importes históricos, los pagos y los saldos;
no recalcula operaciones ya confirmadas. V22 permanece en el historial de
migraciones para permitir actualizar instalaciones existentes.
