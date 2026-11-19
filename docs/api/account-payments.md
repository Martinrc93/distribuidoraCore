# Pagos de cuenta corriente

## Endpoint

`POST /api/customers/{customerId}/account-payments`

Requiere `SALE_PAYMENT` o `ADMIN_ALL`. Un vendedor solo puede registrar pagos de clientes asignados a su perfil. V17 incorpora `SALE_PAYMENT` y lo concede al rol `SELLER`.

Además, solo puede imputar cobros a ventas de sus pedidos. Los pedidos sin
vendedor usan la asignación actual del cliente que recibió el pedido. FIFO aplica el mismo filtro y
una venta de otro vendedor se rechaza con `404`, aunque el cliente esté asignado
al usuario. El administrador mantiene acceso a todas las ventas.

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

- Desde V51 la cuenta corriente es la de cada venta (`sale.sales.account_customer_id`),
  separada del cliente que recibe el pedido. Este endpoint cobra las ventas que
  debe la cuenta del cliente indicado y registra pagos y asientos sobre ella. Sin
  revendedores, esas ventas son los pedidos del propio cliente.
- El importe debe ser positivo, con hasta cuatro decimales, y no puede superar la deuda vigente positiva del cliente.
- Una asignación específica tampoco puede exceder la deuda abierta de la venta indicada.
- La deuda de cada venta se limita al menor valor entre su saldo neto de ledger y `total - paid`.
- FIFO aplica el importe a las ventas más antiguas y puede crear varias asignaciones; cada una crea un pago asociado a una venta.
- La transacción inserta pagos y asientos `CREDIT`, aumenta `sale.paid`, reduce `customer.balance` y registra auditoría `ACCOUNT_PAYMENT_APPLY`.
- Se bloquean las ventas afectadas y el cliente para serializar cobros simultáneos. Si cualquier parte falla, se revierten todas las asignaciones.

Respuesta `201 Created`:

Para usuarios con alcance asignado, `balanceBefore` y `balanceAfter` representan
la deuda abierta de sus pedidos para ese cliente, limitada al saldo global
positivo. Pedidos sin vendedor usan la asignación actual del cliente. El listado,
la ficha y la consulta de saldo usan la misma definición. Para administradores
mantienen el saldo global, incluido un posible crédito. Las actualizaciones
contables siempre modifican el saldo global persistido, en la misma transacción.

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
- `403 FORBIDDEN`: falta `SALE_PAYMENT`/`ADMIN_ALL`.
- `404 NOT_FOUND`: cliente o venta indicada inexistentes o fuera del alcance del vendedor.
- `409 CONFLICT`: el cliente no tiene deuda positiva suficiente, la asignación excede el saldo o la venta no está abierta.

## Pagos combinados y liquidación de cuenta

`POST /api/customers/{customerId}/account-payments/settlement` requiere
`ACCOUNT_SETTLE` o `ADMIN_ALL`. Es independiente de `SALE_PAYMENT`: tener uno
no concede el otro. El listado de Clientes ofrece «Registrar pago» para saldos
positivos y consulta `GET /api/customers/{customerId}/account-balance` antes de
abrir el diálogo compartido con entregas. Esta lectura mínima admite
`ACCOUNT_SETTLE`, `SALE_PAYMENT` o `ADMIN_ALL` y devuelve solo `id`, `name`,
`balance` y `status`; no exige `CUSTOMER_VIEW` ni habilita ficha o historial.

Sin `ADMIN_ALL` se exige un perfil activo y un cliente asignado. Solo se cobran
ventas de pedidos propios, o sin vendedor del cliente asignado; las ventas de
otro vendedor quedan fuera. La liquidación completa salda la deuda visible,
aunque quede deuda global de otras ventas. Un cliente ajeno devuelve `404` y
la falta del permiso de operación devuelve `403`.

```json
{
  "payments": [
    { "amount": 40.00, "method": "CASH" },
    { "amount": 30.00, "method": "BANK_TRANSFER", "transferReference": "TR-123" }
  ]
}
```

Admite entre 1 y 100 pagos válidos. Cada transferencia exige una referencia no
vacía de hasta 100 caracteres. Sin `partial` (o con `false`), la suma debe
coincidir exactamente con el saldo deudor visible actual, verificado después de bloquear
ventas y cliente en el mismo orden que los pagos individuales. Un saldo cambiado,
cero, negativo o un importe incompleto/excesivo devuelve `409` sin registrar cobros.

### Pago parcial

Con `"partial": true` la suma puede ser menor al saldo deudor (nunca mayor). Se
usa el mismo bloqueo, la misma imputación FIFO y la misma transacción única; la
respuesta informa `balanceAfter` con el saldo que queda pendiente. La pantalla de
Clientes («Registrar pago») envía `partial: true` solo cuando el importe es menor
al saldo, y muestra «Pago registrado. Saldo pendiente: …».

```json
{
  "payments": [{ "amount": 20.00, "method": "CASH" }],
  "partial": true
}
```

Todos los medios se imputan por FIFO dentro de una única transacción: si falla
cualquier asignación, se revierten también los pagos anteriores, sus asientos,
los saldos y la auditoría. No cambia el estado de entrega. La respuesta conserva
el formato anterior, con el importe total en `received`, `allocationMode: "FIFO"`
y `balanceAfter` (`0` en una liquidación completa; el saldo restante en un pago parcial).

## Verificación de liquidaciones

Las pruebas cubren pagos combinados, saldo actualizado, permisos, validación,
rollback ante un fallo posterior y reintentos sin doble cobro. El flujo de navegador
verifica 320, 640 y 1280 px, teclado, restauración del foco, estado pendiente y
actualización del listado filtrado.

Los tests PostgreSQL cubren imputación FIFO, asignación específica, límites por deuda, referencia de transferencia y concurrencia sin doble cobro. También verifican aislamiento entre vendedores con ventas del mismo cliente, saldos restringidos, pagos combinados con ACCOUNT_SETTLE sin CUSTOMER_VIEW ni SALE_PAYMENT y autorización por API. La matriz de permisos se documenta en [Clientes y cuentas](customer-permissions.md).

## Cuentas de revendedor (Fase 9.2)

Los pagos individuales, FIFO, por venta y las liquidaciones sobre la cuenta de un
cliente `RESELLER` requieren `ADMIN_ALL`; otros usuarios reciben `403` aunque
tengan `SALE_PAYMENT` o `ACCOUNT_SETTLE` y el cliente esté asignado. FIFO recorre
todas las ventas abiertas de la cuenta, de cualquiera de sus clientes, por fecha
de venta y UUID. `GET /api/customers/{customerId}/debts` agrega `customerId` y
`customer` (cliente que recibió cada pedido).
