# Edición de pedidos confirmados

## Endpoint

`PUT /api/orders/{orderId}`

Requiere JWT con la autoridad `ADMIN_ALL`. El request reemplaza por completo las líneas comerciales del pedido y su venta asociada. No cambia el cliente, el vendedor, los identificadores ni los números del pedido/venta.

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

`priceListId` es opcional. Si se omite, cada precio se resuelve con la lista asignada al cliente o `GENERAL`; también aplica el fallback de listas ya definido por pricing. El override de precio y los descuentos por línea/orden requieren `ADMIN_ALL`. Las cantidades deben ser positivas y múltiplos de `0.5`; una solicitud vacía no está permitida.

## Reglas comerciales

- Solo se pueden editar pedido y venta en estado `CONFIRMED`. Pedidos entregados o cancelados devuelven `409 CONFLICT`.
- `paid` y las filas de pago existentes se conservan. El total nuevo no puede ser menor que el importe ya pagado; no se generan devoluciones monetarias.
- Se reemplazan los snapshots de líneas de pedido y venta con los precios y descuentos calculados para esta edición.
- El ledger de la venta se ajusta por la diferencia entre el total nuevo y el total anterior con un asiento append-only `DEBIT` o `CREDIT`; así se conservan pagos a cuenta previamente imputados. El balance agregado del cliente se ajusta por el mismo delta.
- La diferencia de cantidades por producto genera movimientos compensatorios: más unidades vendidas crea `SALE` negativo; reducción o eliminación de unidades crea `SALE_CANCELLATION` positivo.
- Todos los cambios de líneas, stock, ledger, balance y auditoría `ORDER_EDIT` ocurren en una transacción. Si falla una parte, no persisten cambios parciales.
- La cancelación posterior revierte el efecto neto por producto de movimientos `SALE` y `SALE_CANCELLATION`, incluso si el pedido tuvo ediciones previas.

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
- `403 FORBIDDEN`: usuario sin `ADMIN_ALL`.
- `404 NOT_FOUND`: pedido inexistente.
- `409 CONFLICT`: estado no editable o total recalculado menor a `paid`.

## Verificación

Los tests unitarios cubren autorización administrativa. Las pruebas opt-in PostgreSQL validan edición de líneas, deltas de stock, conservación de pagos, conciliación de deuda, rollback del mínimo pagado y cancelación posterior sin sobre-restaurar stock.
