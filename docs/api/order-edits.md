# Edición de pedidos confirmados

## Endpoint

`PUT /api/orders/{orderId}`

Requiere JWT con `ORDER_EDIT` o `ADMIN_ALL`, más acceso al pedido asignado para
usuarios sin acceso global. El request reemplaza por completo las líneas comerciales del pedido y su venta asociada. No cambia el cliente, el vendedor, los identificadores ni los números del pedido/venta.

```json
{
  "priceListId": null,
  "lines": [
    {
      "productId": "00000000-0000-0000-0000-000000000001",
      "quantity": 2.0,
      "lineDiscountPercent": 0,
      "unitPriceOverride": null
    }
  ],
  "orderDiscountPercent": 0
}
```

`priceListId` es opcional. Si se omite, cada precio se resuelve con la lista asignada al cliente o `GENERAL`; también aplica el fallback de listas ya definido por pricing. Solo se aplican los porcentajes manuales enviados: cero conserva el precio completo y no activa reglas automáticas. Cambiar precios manuales requiere `ORDER_PRICE_OVERRIDE`; cambiar descuentos requiere `ORDER_DISCOUNT` y cambiar la lista, `ORDER_PRICE_LIST_CHANGE`. Conservar los snapshots existentes no exige esos permisos adicionales. Las cantidades deben ser positivas y múltiplos de `0.5`; una solicitud vacía no está permitida. Ver [permisos comerciales](commerce-permissions.md).

## Reglas comerciales

- Solo se pueden editar pedido y venta en estado `CONFIRMED`. Pedidos entregados o cancelados devuelven `409 CONFLICT`.
- `paid` y las filas de pago existentes se conservan. El total nuevo no puede ser menor que el importe ya pagado; no se generan devoluciones monetarias.
- Se reemplazan los snapshots de líneas de pedido y venta con los precios y descuentos calculados para esta edición.
- El ledger de la venta se ajusta por la diferencia entre el total nuevo y el total anterior con un asiento append-only `DEBIT` o `CREDIT`; así se conservan pagos a cuenta previamente imputados. El balance agregado del cliente se ajusta por el mismo delta.
- Si el pedido está `PREPARED`, la diferencia de cantidades por producto genera movimientos compensatorios: más unidades vendidas crea `SALE` negativo; reducción o eliminación de unidades crea `SALE_CANCELLATION` positivo. En `PENDING` se editan las líneas sin mover stock.
- Los deltas de cantidades se aplican al saldo único por producto; el pedido no tiene atributo de depósito.
- Todos los cambios de líneas, stock, ledger, balance y auditoría `ORDER_EDIT` ocurren en una transacción. Si falla una parte, no persisten cambios parciales.
- La cancelación posterior revierte el efecto neto por producto de movimientos `SALE` y `SALE_CANCELLATION`, incluso si el pedido tuvo ediciones previas.
- La reversión agrupa los movimientos por producto para restaurar el saldo único sin sobre-restaurar ediciones anteriores.

## Reactivar un pedido cancelado

`POST /api/orders/{orderId}/reactivate` requiere `ORDER_REACTIVATE` o `ADMIN_ALL`,
con acceso al pedido, y reactiva el pedido
y su venta de `CANCELLED` a `CONFIRMED`. En el detalle del pedido aparece la
acción **Reactivar pedido** para usuarios con ese permiso.

La reactivación exige que no existan pagos asociados. En una transacción deja el
pedido pendiente de preparación sin mover stock, agrega un débito
por el total de la venta al ledger y aumenta el saldo del cliente. Los
movimientos y asientos anteriores se conservan como historial append-only. La
respuesta es `204 No Content`; si los estados no son ambos `CANCELLED` o hay
pagos registrados, responde `409 CONFLICT`.

Respuesta `200 OK`:

```json
{
  "orderId": "00000000-0000-0000-0000-000000000002",
  "saleId": "00000000-0000-0000-0000-000000000003",
  "total": 20.0000,
  "paid": 0.0000,
  "balance": 20.0000
}
```

`balance` en esta respuesta es el saldo neto de cuenta corriente registrado para la venta tras la edición. `paid` continúa representando pagos monetarios (`CASH`/`BANK_TRANSFER`); los créditos ya aplicados a la cuenta corriente se mantienen.

## Errores relevantes

- `400 INVALID_REQUEST`: cuerpo inválido o cantidad no permitida.
- `401`: JWT ausente o inválido.
- `403 FORBIDDEN`: falta el permiso de la acción o el cambio comercial solicitado.
- `404 NOT_FOUND`: pedido inexistente o ajeno al alcance asignado.
- `409 CONFLICT`: estado no editable o total recalculado menor a `paid`.

## Verificación

Los tests unitarios y PostgreSQL cubren permisos independientes y alcance asignado,
además de edición de líneas, snapshots históricos, deltas de stock, conservación
de pagos, conciliación de deuda, rollback y cancelación posterior.

## Cancelar un pedido

`POST /api/orders/{orderId}/cancel` requiere `ORDER_CANCEL` o `ADMIN_ALL` y acceso
al pedido. Conserva la prohibición de cancelar ventas pagadas, la compensación
del stock preparado y los asientos contables; devuelve `204 No Content`.
