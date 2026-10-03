# Cobros durante la entrega

## Endpoint

`POST /api/orders/{orderId}/delivery-attempts`

Requiere `SALE_DELIVER` o `ADMIN_ALL`; los vendedores deben tener acceso al pedido asignado. La solicitud anterior se mantiene válida. En un intento `DELIVERED` se puede incluir una lista opcional de cobros:

```json
{
  "result": "DELIVERED",
  "observation": null,
  "payments": [
    { "method": "CASH", "amount": 5.00 },
    { "method": "BANK_TRANSFER", "amount": 10.00 }
  ],
  "transferReference": "TR-DELIVERY-123"
}
```

Los medios aceptados durante la entrega son `CASH` y `BANK_TRANSFER`; cada importe debe ser positivo y tener hasta cuatro decimales. `transferReference` es opcional, admite hasta 100 caracteres y se guarda con los pagos por transferencia de esa solicitud. Si se informa, la solicitud debe contener al menos un pago `BANK_TRANSFER`. No se exige referencia para aceptar una transferencia.

Un resultado `FAILED` no admite cobros ni referencia de transferencia y sigue exigiendo una observación no vacía. Si no se envía `payments` al marcar `DELIVERED`, el pedido se entrega sin nuevos cobros.

## Cuenta corriente y atomicidad

`previousDebtAmount` es opcional, no negativo y admite hasta cuatro decimales.
Permite seleccionar un importe de deuda de ventas anteriores del mismo cliente,
sin crear un nuevo débito ni aumentar el total de esta venta. El detalle expone
`sale.previousDebtAvailable`, calculado con deuda contable vigente y `total - paid`.
Solo incluye ventas confirmadas o entregadas anteriores según `(created_at, id)`;
para vendedores, solo pedidos propios o sin vendedor del cliente asignado.

Por ejemplo, con saldo de esta venta de 100 y `previousDebtAmount: 40`, un pago
de 140 cancela esta venta y aplica 40 a las anteriores por antigüedad. Un pago
de 110 aplica 100 a esta venta y 10 a las anteriores. Cada asignación conserva
el medio y número de transferencia en su venta, aumenta su `paid`, acredita su
ledger y reduce una sola vez el saldo del cliente. Seleccionar una deuda anterior
ya pagada o un importe mayor al disponible devuelve `409` y revierte la entrega.
`FAILED` no admite deuda anterior. Omitir el campo conserva el comportamiento previo.

La UI usa «Pago», oculta observación para entregas exitosas y solicita el número
de transferencia dentro del bloque de pago. Ofrece importe editable de deuda
anterior, «Pagar total» y «Pagar solo esta venta». La API conserva compatibilidad
con transferencias históricas sin referencia.

- Las ventas afectadas se bloquean por antigüedad antes del cliente durante el registro.
- Los pagos se agregan a `payment.payments` y aumentan `sale.paid` sin cambiar el total de venta.
- El importe cobrado se registra como `CREDIT` en el ledger de esa venta y reduce `customer.balance`. La deuda no cobrada permanece en la cuenta corriente.
- Cada asignación no puede superar ni el saldo contable positivo de su venta ni `total - paid`; el total no puede superar el saldo de esta venta más la deuda anterior seleccionada.
- El intento, pagos, crédito, balance y cambio de estado a `DELIVERED` se guardan en una sola transacción. Un error revierte todos esos efectos.
- La operación registra auditoría `DELIVERY_ATTEMPT` con monto recibido, monto pagado acumulado y deuda restante.

La respuesta continúa siendo `204 No Content`. El detalle de venta y el listado de pagos exponen `transferReference` cuando existe.

## Errores relevantes

- `400 INVALID_REQUEST`: método o importe inválido, referencia sin transferencia o cobro adjunto a un intento fallido.
- `401`: JWT ausente o inválido.
- `403 FORBIDDEN`: falta `SALE_DELIVER`/`ADMIN_ALL` o el vendedor no tiene acceso al pedido.
- `404 NOT_FOUND`: pedido inexistente.
- `409 CONFLICT`: estado terminal o cobro superior al saldo pendiente.

## Persistencia

La migración `V16__add_payment_transfer_reference.sql` agrega `payment.payments.transfer_reference` nullable. Las filas de pago previas conservan `NULL`.
