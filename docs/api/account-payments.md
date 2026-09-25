# Pagos de cuenta corriente

## Endpoint

`POST /api/customers/{customerId}/account-payments`

Requiere `SALE_PAYMENT` o `ADMIN_ALL`. Un vendedor solo puede registrar pagos de clientes asignados a su perfil. V17 incorpora `SALE_PAYMENT` y lo concede al rol `SELLER`.

```json
{
  "amount": 35.00,
  "method": "CASH",
  "transferReference": null,
  "saleId": null
}
```

Si `saleId` se informa, el pago se asigna a esa venta. Si se omite, se distribuye por FIFO entre las ventas `CONFIRMED`/`DELIVERED` con deuda, ordenadas por fecha de creación y UUID. Los métodos permitidos son `CASH` y `BANK_TRANSFER`; la referencia bancaria es opcional, admite hasta 100 caracteres y solo se acepta junto a `BANK_TRANSFER`.

## Reglas de imputación

- El importe debe ser positivo, con hasta cuatro decimales, y no puede superar la deuda vigente positiva del cliente.
- Una asignación específica tampoco puede exceder la deuda abierta de la venta indicada.
- La deuda de cada venta se limita al menor valor entre su saldo neto de ledger y `total - paid`.
- FIFO aplica el importe a las ventas más antiguas y puede crear varias asignaciones; cada una crea un pago asociado a una venta.
- La transacción inserta pagos y asientos `CREDIT`, aumenta `sale.paid`, reduce `customer.balance` y registra auditoría `ACCOUNT_PAYMENT_APPLY`.
- Se bloquean las ventas afectadas y el cliente para serializar cobros simultáneos. Si cualquier parte falla, se revierten todas las asignaciones.

Respuesta `201 Created`:

```json
{
  "customerId": "00000000-0000-0000-0000-000000000001",
  "received": 35.0000,
  "balanceBefore": 70.0000,
  "balanceAfter": 35.0000,
  "allocationMode": "FIFO",
  "allocations": [
    { "saleId": "00000000-0000-0000-0000-000000000002", "saleNumber": "SAL-001", "paymentId": "00000000-0000-0000-0000-000000000003", "amount": 30.0000 },
    { "saleId": "00000000-0000-0000-0000-000000000004", "saleNumber": "SAL-002", "paymentId": "00000000-0000-0000-0000-000000000005", "amount": 5.0000 }
  ]
}
```

## Errores relevantes

- `400 INVALID_REQUEST`: método, importe o referencia inválidos.
- `401`: JWT ausente o inválido.
- `403 FORBIDDEN`: falta `SALE_PAYMENT`/`ADMIN_ALL` o el cliente no está asignado al vendedor.
- `404 NOT_FOUND`: cliente o venta indicada inexistentes.
- `409 CONFLICT`: el cliente no tiene deuda positiva suficiente, la asignación excede el saldo o la venta no está abierta.

## Verificación

Los tests PostgreSQL cubren imputación FIFO, asignación específica, límites por deuda, referencia de transferencia y concurrencia sin doble cobro.
