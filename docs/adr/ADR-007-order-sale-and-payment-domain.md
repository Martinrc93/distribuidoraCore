# ADR-007: Pedido, venta, pagos y cuenta corriente

## Status

Accepted

## Context

El usuario carga un pedido en el frontend, selecciona un cliente y obtiene su
vendedor asignado como valor inicial. Un administrador puede seleccionar otro
perfil seller existente para el pedido; los demás usuarios deben conservar la
atribución a su perfil seller autenticado. El pedido no se persiste como
borrador: recién al confirmar se crea el pedido confirmado, la venta y la
información de pago. Desde V42 el impacto de stock ocurre al preparar.

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
→ registrar Payment o CustomerAccountLedger
→ preparar (explícitamente o al entregar) y afectar Inventory
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
- La modificación conserva snapshots históricos y genera deltas de stock solo si está preparado.
- `fulfillment_status` (`PENDING` / `PREPARED`) es independiente del estado comercial.
- Preparar requiere `ORDER_PREPARE` o `ADMIN_ALL`; V42 lo asigna a `ADMIN` y `SELLER`.
- Entregar desde pendiente prepara y descuenta stock en la misma transacción.
- La cancelación revierte los movimientos existentes y vuelve la preparación a pendiente.
  Reactivar restaura la deuda sin descontar stock.
- Los pedidos históricos activos quedan preparados por equivalencia sin un nuevo descuento;
  `legacy_fulfillment` los identifica sin inventar fecha ni usuario de preparación.
- Los pedidos creados por vendedores requieren revisión administrativa, que no bloquea
  creación, preparación, cobro ni entrega. Hasta entonces conservan el distintivo.
  La revisión guarda fecha/administrador y auditoría `ORDER_REVIEW`, sin cambiar
  estado comercial, stock ni importes. Repetirla es idempotente.
- `ORDER_REVIEW` es un permiso administrativo, asignado solo a `ADMIN`.
- Los precios aplicados se guardan en `OrderItem` y `Sale`.
- El cliente puede tener una lista de precios opcional.
- Si no tiene lista, se utiliza `GENERAL`.
- La lista puede cambiarse durante la creación del pedido: `ADMIN_ALL` o el permiso
  `ORDER_PRICE_LIST_CHANGE` (otorgado a `ADMIN` y `SELLER` desde V39, revocable por rol).
- `sellerId` es opcional en la confirmación y solo `ADMIN_ALL` puede elegirlo;
  si se omite, se utiliza el vendedor asignado al cliente.
- El vendedor resuelto se persiste en `orders.orders.seller_id`. Para usuarios
  no administradores el servidor exige el perfil seller autenticado, aunque el
  request incluya otro `sellerId`.
- La atribución de un pedido no cambia la asignación del cliente en
  `customer.customers.seller_id`.
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

Actualización (2026-11-18, V51, [ADR-010](ADR-010-reseller-debt-holder.md)): la
cuenta corriente pertenece a la cuenta que debe cada venta
(`sale.sales.account_customer_id`), separada del cliente que recibe el pedido.
Débitos, pagos, saldo, FIFO y límite de crédito usan esa cuenta. Mientras no
haya revendedores, la cuenta es el cliente del pedido.

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
