# Devoluciones de ventas

## Registrar una devolución

`POST /api/sales/{saleId}/returns`

Requiere la autoridad `ADMIN_ALL`. Solo acepta ventas y pedidos con estado `DELIVERED`.

```json
{
  "reason": "Producto dañado al recibirlo",
  "items": [
    {
      "saleItemId": "UUID de la línea de venta",
      "quantity": 1.0
    }
  ]
}
```

`reason` es obligatorio y admite hasta 500 caracteres. `items` debe contener al menos una línea. Cada `saleItemId` debe pertenecer a la venta indicada y aparecer una sola vez. Las cantidades deben ser positivas, múltiplos de `0.5`, y no pueden superar la cantidad vendida menos las devoluciones anteriores.

La operación devuelve `201 Created`:

```json
{
  "returnId": "UUID de la devolución",
  "saleId": "UUID de la venta",
  "reason": "Producto dañado al recibirlo",
  "items": [
    {
      "saleItemId": "UUID de la línea de venta",
      "productId": "UUID del producto",
      "quantity": 1.0000
    }
  ]
}
```

La cabecera, las líneas, el movimiento de inventario `RETURN` y la auditoría se guardan en una transacción. La venta se bloquea durante la validación para serializar devoluciones simultáneas. Un error al actualizar cualquier saldo revierte toda la operación. La devolución repone el depósito guardado en la venta, incluso si se encuentra inactivo. Los productos inactivos pueden volver al stock; el balance histórico del depósito debe existir.

Las cantidades devueltas y el stock quedan registrados, pero esta operación no emite reembolsos ni créditos de cuenta corriente y no cambia el total pagado de la venta. La devolución financiera queda pendiente de una decisión de negocio.

Respuestas de error:

- `400 INVALID_REQUEST`: payload inválido, cantidad no válida o línea ajena a la venta.
- `401/403`: usuario no autenticado o sin `ADMIN_ALL`.
- `404`: venta inexistente.
- `409 CONFLICT`: venta todavía no entregada o cantidad superior al saldo disponible para devolución.

Las devoluciones requieren las tablas creadas en `V15__add_sale_returns.sql` y el depósito persistido por venta en V23.
