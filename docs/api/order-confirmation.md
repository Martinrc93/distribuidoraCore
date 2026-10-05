# Confirmación del pedido

## Lista de precios

`priceListId` es opcional. Sin el campo o con `null`, se usa la lista asignada
al cliente, o `GENERAL` si no tiene asignación. Un vendedor puede enviar esa
misma lista, pero enviar otra produce `403 FORBIDDEN` antes de crear el pedido
o mover stock. Solo `ADMIN_ALL` puede elegir una lista diferente. Se mantienen
la validación de lista activa y el fallback de precios definido en pricing.

## Fecha de registro

`POST /api/orders/confirm` acepta el campo opcional `orderDate` en formato ISO
`yyyy-MM-dd`, por ejemplo `"orderDate": "2026-09-29"`. Requiere los permisos
habituales `ORDER_CREATE` o `ADMIN_ALL`. Admite fechas pasadas y futuras válidas
entre los años 0001 y 9999; las fechas inválidas producen `400`.

Con `orderDate`, el pedido y su venta asociada se registran en ese día de
`America/Argentina/Buenos_Aires`, conservando la hora local de la operación.
Sin el campo o con `null`, se registra el instante actual, manteniendo la
compatibilidad con los clientes existentes. La fecha se guarda en `created_at`
de ambos registros y se utiliza en sus listados, detalles y filtros.

La fecha elegida forma parte de la huella de idempotencia: reutilizar una clave
con otra fecha produce `409 IDEMPOTENCY_CONFLICT`. Los movimientos de inventario,
cobros, cuenta corriente y auditoría mantienen su fecha real de ejecución. Los
precios se calculan con las reglas vigentes al confirmar. La edición de un
pedido conserva su fecha de registro.
