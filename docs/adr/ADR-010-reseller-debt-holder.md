# ADR-010: Titular de la deuda separado del destinatario del pedido

## Status

Accepted (2026-11-18). Las reglas de negocio P1–P9 del
[diseño de la Fase 9](../superpowers/specs/2026-11-18-fase-9-revendedores-design.md)
se respondieron el 2026-11-18. Modifica la regla de cuenta corriente de
[ADR-007](ADR-007-order-sale-and-payment-domain.md).

Implementación: la etapa 9.1 (V51) aplica los puntos 1 a 5 y 7, con la cuenta
igual al cliente del pedido. La etapa 9.2 (V52) aplica los puntos 6 y 8, y la
etapa 9.3 (V53), el punto 9.

## Context

ADR-007 vincula pedido, venta, pagos y cuenta corriente al mismo cliente.
`sale.sales.customer_id` define a la vez quién recibe el pedido, quién debe la
venta y a quién se acreditan los pagos. El ledger (`customer.account_ledger`) y
el saldo persistido (`customer.customers.balance`) copian ese cliente en siete
sentencias de tres servicios. FIFO, saldos con alcance, estado de deuda y
Dashboard filtran por él.

La regla confirmada para revendedores exige que los pedidos de sus clientes
debiten la cuenta del revendedor. El cliente final debe seguir siendo el
destinatario para entrega, documentos, numeración e historial de compras. Ambas
instancias comparten código: sin revendedores, nada puede cambiar.

## Decision

1. La venta guarda su titular en `sale.sales.account_customer_id`. Se fija al
   confirmar: el revendedor del destinatario si lo tiene; si no, el destinatario.
   No cambia después. Las reasignaciones afectan solo pedidos nuevos.
2. `orders.customer_id` y `sales.customer_id` siguen identificando al
   destinatario.
3. Todo asiento del ledger y todo pago de una venta pertenecen a su titular,
   mediante FK compuestas `(sale_id, customer_id) → sales (id, account_customer_id)`.
   `customers.balance` sigue siendo el saldo de la cuenta del titular.
4. La cuenta corriente se concentra en el módulo `payment`. `AccountLedger`
   escribe asientos, saldos y pagos con el titular leído de la venta bloqueada;
   cobros FIFO o por venta, saldo visible y deuda anterior de entregas viven en
   el mismo módulo. Ningún caso de uso recibe el titular desde el request.
5. FIFO opera por cuenta titular, ordenado por `(created_at, id)` de la venta.
6. El revendedor es un cliente con `kind = 'RESELLER'`, sin usuario propio.
   Sus clientes lo referencian con `customers.reseller_id`, de forma exclusiva y
   sin cadenas. Marcar y asociar exige `RESELLER_MANAGE`, reservado a ADMIN.
7. El alcance de usuarios asignados sigue definido por el vendedor del pedido.
   Para pedidos sin vendedor, el respaldo usa la asignación del destinatario.
8. Los cobros sobre cuentas de revendedor requieren `ADMIN_ALL`, y las ventas
   cargadas a un revendedor no admiten devoluciones.
9. Un plan de comisión puede asignarse a un revendedor. Devenga al entregar las
   ventas cargadas a su cuenta, con el plan asignado en ese momento y además de la
   comisión del vendedor. Movimientos y liquidaciones pertenecen a un solo
   beneficiario, vendedor o revendedor.

## Alternatives considered

### Pedido a nombre del revendedor con destinatario aparte

`orders.customer_id` sería el revendedor y la entrega iría en otro campo.
Descartada: cambia el significado de `customer_id` en numeración, historial,
reportes de compras, documentos y alcance. El cliente final perdería su
historial propio.

### Entidad de cuenta independiente del cliente

`customer.accounts` con saldo y ledger por cuenta. Descartada por ahora: logra
lo mismo con una migración mayor, que mueve saldos y reescribe todas las
lecturas. Guardar el titular en la venta permite extraerla más adelante sin
cambiar reglas.

### Resolver el titular en cada lectura

Sin guardarlo, a partir de la asociación vigente. Descartada: una reasignación
movería deuda histórica sin asientos, contra el ledger inmutable de ADR-007.

### Usar la asignación de vendedor como asociación

`customers.seller_id` apuntaría al perfil del revendedor, como sugería la
propuesta inicial del plan (rol `RESELLER` sobre `SellerProfile`). Descartada:
mezcla quién atiende con quién paga y exige un usuario por revendedor, aunque
el revendedor no necesita acceso al sistema (P2). Si más adelante lo necesitara,
puede agregarse enlazando un perfil con la cuenta del revendedor.

### Traspasar la deuda al reasignar

Descartada: el ledger exige `sale_id` y las FK compuestas impiden cambiar el
titular de una venta con asientos o pagos.

## Consequences

### Positivas

- Una sola regla para todos los clientes: titular = destinatario, salvo
  clientes asociados a un revendedor.
- La base de datos impide debitar o acreditar una venta en otra cuenta.
- El ledger queda en un solo módulo, como indican los límites de módulos.
- Dashboard, deudores y límite de crédito reflejan la deuda del revendedor sin
  lógica especial.

### Negativas

- Hay que revisar todas las consultas de deuda para filtrar por titular y
  mostrar el destinatario.
- Las confirmaciones de clientes de un mismo revendedor se serializan en su cuenta.
- La migración falla si existen asientos o pagos que no coinciden con el
  cliente de su venta.
- Sin traspasos, corregir un titular equivocado exige cancelar y volver a cargar
  el pedido, solo posible si no tiene pagos.

## Pending decisions

Ninguna. Los supuestos S1–S6 se confirmaron el 2026-11-18 y S7, el 2026-11-19.
