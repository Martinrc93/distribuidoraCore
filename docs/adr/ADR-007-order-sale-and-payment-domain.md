# ADR-007: Pedido, venta, pagos y cuenta corriente

## Status

Accepted

## Context

El usuario carga un pedido en el frontend, selecciona un cliente y obtiene su
vendedor asignado. El pedido no se persiste como borrador: recién al confirmar
se crea el pedido confirmado, la venta, el impacto de stock y la información de
pago.

El sistema necesita listas de precios, overrides, descuentos acumulables,
pagos parciales, pagos combinados y cuenta corriente interna.

## Decision

El flujo de creación será una única operación transaccional:

```text
formulario no persistido
→ seleccionar cliente
→ obtener vendedor asignado
→ cargar líneas y descuentos
→ confirmar
→ crear Order CONFIRMED
→ crear Sale
→ afectar Inventory
→ registrar Payment o CustomerAccountLedger
```

Los únicos estados de negocio serán:

```text
CONFIRMED
DELIVERED
CANCELLED
```

Los intentos fallidos de entrega no son estados principales. Se almacenan como
`DeliveryAttempt` con observación y el pedido permanece `CONFIRMED`.

Reglas:

- `DELIVERED` y `CANCELLED` son terminales.
- Vendedor y administrador pueden marcar `DELIVERED`.
- Solo el administrador puede cancelar.
- Una venta pagada no se cancela.
- Una venta parcialmente pagada puede entregarse.
- El vendedor no puede modificar pedidos, precios ni descuentos.
- El administrador puede modificar cualquier pedido.
- La modificación conserva snapshots históricos y genera deltas de stock.
- Los precios aplicados se guardan en `OrderItem` y `Sale`.
- El cliente puede tener una lista de precios opcional.
- Si no tiene lista, se utiliza `GENERAL`.
- La lista puede cambiarse durante la creación del pedido.
- Los descuentos de línea y total son acumulables.

Pagos:

```text
CASH
BANK_TRANSFER
CUSTOMER_ACCOUNT
```

Se permiten pagos parciales y combinados. Los pagos se aplican manualmente a
una deuda específica o automáticamente al saldo más antiguo si no se selecciona
una deuda. No se permiten pagos superiores al saldo ni saldos a favor.

El límite de crédito es global y configurable. No bloquea el pedido: muestra
una advertencia con el saldo actual y el saldo proyectado. El administrador
puede continuar y la advertencia queda auditada.

## Alternatives considered

### Venta como alternativa independiente al pedido

Descartada. La venta siempre nace de la confirmación de un pedido.

### Persistir borradores

Descartado inicialmente. El formulario previo a confirmar vive en el frontend.

### Saldo de deuda editable

Descartado. La cuenta corriente se calcula desde un ledger inmutable.

### Pago excedente como saldo a favor

Descartado. No se permiten pagos superiores ni créditos automáticos.

## Consequences

### Positivas

- Flujo comercial simple y transaccional.
- Precios y descuentos históricos reproducibles.
- Pagos y deuda auditables.
- El límite de crédito informa sin bloquear la operación.

### Negativas

- Si se pierde el formulario antes de confirmar, no existe pedido.
- Editar una venta confirmada requiere recalcular deltas, pagos y stock.
- La cuenta corriente requiere una interfaz de conciliación clara.

## Pending decisions

- Regla detallada para modificar una venta parcialmente pagada cuando el nuevo
  total sería inferior a lo ya pagado: inicialmente la operación deberá
  rechazarse.
- Política detallada de devolución para futuras fases.
