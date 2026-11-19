# Confirmación del pedido

Desde V42 la confirmación crea el pedido con `fulfillmentStatus: PENDING`, sin
descontar stock. Los pagos y la deuda se registran como parte de la confirmación.
El stock se descuenta al preparar explícitamente o al entregar un pendiente;
ver [preparación y revisión administrativa](order-fulfillment.md).

## Lista de precios

`priceListId` es opcional. Sin el campo o con `null`, se usa la lista asignada
al cliente, o `GENERAL` si no tiene asignación. Un vendedor puede enviar esa
misma lista, pero enviar otra produce `403 FORBIDDEN` antes de crear el pedido
o mover stock, salvo que tenga `ORDER_PRICE_LIST_CHANGE` o `ADMIN_ALL`. Se mantienen
la validación de lista activa y el fallback de precios definido en pricing.

Los precios manuales requieren `ORDER_PRICE_OVERRIDE` y los descuentos de línea
y pedido requieren `ORDER_DISCOUNT`, además de `ORDER_CREATE` para confirmar.
`ADMIN_ALL` autoriza ambas acciones. Estos permisos conservan el alcance del
cliente y vendedor asignados; no habilitan elegir datos de otros usuarios.

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

## Cuenta que debe el pedido (Fase 9)

La venta guarda la cuenta que la debe en `account_customer_id` (V51). Es el
revendedor del cliente si lo tiene (V52) o el propio cliente. Esa cuenta recibe
el débito, los pagos y el cálculo del límite de crédito; el pedido, su numeración,
la lista de precios y el vendedor siguen siendo los del cliente que lo recibe.

Con un revendedor como cuenta:

- `previousBalanceAmount` mayor a cero devuelve `400`: el remito es del cliente,
  que no le debe a la distribuidora.
- Los pagos (`payments` con efectivo o transferencia y `previousDebtPayments`)
  requieren `ADMIN_ALL`; sin él, `403` antes de registrar nada. La deuda anterior
  se imputa por FIFO en la cuenta del revendedor.
- Un revendedor inactivo impide confirmar (`409`).
- Si el revendedor del cliente cambia durante la confirmación, se responde `409`
  y no se registra nada; el reintento usa la asignación vigente.

La auditoría `ORDER_CONFIRM` agrega `accountCustomerId` cuando la cuenta no es el
cliente. La edición de un pedido conserva su cuenta y rechaza agregar saldo anterior.
