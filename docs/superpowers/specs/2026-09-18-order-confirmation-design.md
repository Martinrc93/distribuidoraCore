# Diseño: Confirmación atómica de pedidos

## Objetivo

Implementar la confirmación de un pedido como una única transacción local que resuelva precios, cree snapshots de pedido y venta, afecte inventario, registre pagos o cuenta corriente y sea idempotente ante reintentos.

## Alcance

Incluye:

- `POST /api/orders/confirm`.
- No persistir borradores.
- Resolución de lista y precio mediante Pricing.
- Descuentos de línea y descuento total.
- Snapshots de producto, lista, precio y descuentos en pedido y venta.
- Creación de pedido `CONFIRMED` y venta asociada.
- Movimientos de inventario `SALE` con locking pesimista.
- Pagos `CASH` y `BANK_TRANSFER`.
- Débito de cuenta corriente `CUSTOMER_ACCOUNT`.
- Pagos parciales y combinados.
- Idempotencia mediante clave única.
- Auditoría de la confirmación.

No incluye todavía:

- Entrega y `DELIVERED`.
- Cancelación y reversión `SALE_CANCELLATION`.
- Edición de pedidos confirmados.
- Outbox y procesamiento asíncrono.
- Documentos, WhatsApp y descuentos promocionales avanzados.

## Request

`POST /api/orders/confirm`

Requiere JWT con `ORDER_CREATE`. Los overrides de precio o descuento requieren además `ADMIN_ALL`.

```json
{
  "idempotencyKey": "checkout-2026-09-18-0001",
  "customerId": "00000000-0000-0000-0000-000000000001",
  "priceListId": null,
  "orderDiscountPercent": 5.00,
  "lines": [
    {
      "productId": "00000000-0000-0000-0000-000000000002",
      "quantity": 2.0,
      "lineDiscountPercent": 0,
      "unitPriceOverride": null
    }
  ],
  "payments": [
    { "method": "CASH", "amount": 100.00 },
    { "method": "CUSTOMER_ACCOUNT", "amount": 50.00 }
  ]
}
```

Reglas del request:

- `idempotencyKey` es obligatorio, no vacío y máximo 100 caracteres.
- Debe existir al menos una línea.
- Las cantidades deben ser positivas y múltiplos de `0.5`.
- Los descuentos son porcentajes entre `0` y `100`, con cuatro decimales como máximo.
- `unitPriceOverride` solo puede enviarlo un administrador y no puede ser negativo.
- `payments` es opcional. Si se omite o queda vacío, el total se registra en cuenta corriente.
- La suma de pagos no puede superar el total final.
- El importe con método `CUSTOMER_ACCOUNT` genera débito de cuenta corriente.
- `CASH` y `BANK_TRANSFER` generan registros en `payment.payments` y aumentan `sale.paid`.

## Transacción

`OrderConfirmationService.confirm` será `@Transactional` y ejecutará:

1. Validar formato, permisos para overrides y clave de idempotencia.
2. Buscar una orden existente por `idempotency_key`.
3. Si existe, devolver su resultado original; si la clave fue usada con otro payload, devolver `409`.
4. Validar cliente activo y resolver lista mediante Pricing.
5. Resolver precios y calcular subtotales, descuentos y total con `BigDecimal`.
6. Bloquear balances de inventario por `product_id` en orden UUID estable.
7. Actualizar saldos y registrar movimientos `SALE` con referencia a la orden.
8. Crear `orders.orders` en estado `CONFIRMED`.
9. Crear `orders.order_items` con snapshots.
10. Crear `sale.sales` y `sale.sale_items` con los mismos snapshots.
11. Crear pagos monetarios y débitos de cuenta corriente.
12. Actualizar `customer.customers.balance` con el débito de cuenta corriente.
13. Registrar auditoría `ORDER_CONFIRM`.
14. Confirmar la transacción y devolver identificadores.

Si cualquier paso falla, se revierten orden, venta, pagos, ledger, balance y stock.

## Persistencia

La migración V6 agregará:

### `orders.orders`

- `idempotency_key VARCHAR(100) NULL UNIQUE`.
- La columna es nullable para conservar pedidos demo existentes.

### `orders.order_items` y `sale.sale_items`

- `price_list_id UUID NULL`.
- `price_list_code VARCHAR(40) NOT NULL`.
- `line_discount_percent NUMERIC(19,4) NOT NULL DEFAULT 0`.
- `unit_price` y `line_total` existentes se conservan como snapshots.

### `customer.account_ledger`

- `id UUID PRIMARY KEY`.
- `customer_id UUID NOT NULL`.
- `sale_id UUID NOT NULL`.
- `entry_type VARCHAR(20) NOT NULL`, limitado a `DEBIT` y `CREDIT`.
- `amount NUMERIC(19,4) NOT NULL CHECK (amount > 0)`.
- `created_at TIMESTAMPTZ NOT NULL`.
- Los débitos de confirmación son append-only.

## Cálculo

Para cada línea:

```text
base = quantity * resolvedUnitPrice
lineTotal = base * (1 - lineDiscountPercent / 100)
subtotal = sum(base)
lineDiscount = sum(base - lineTotal)
totalBeforeOrderDiscount = sum(lineTotal)
orderDiscount = totalBeforeOrderDiscount * orderDiscountPercent / 100
total = totalBeforeOrderDiscount - orderDiscount
```

Los importes se redondean a cuatro decimales con `RoundingMode.HALF_UP` en cada total persistido.

## Idempotencia

- La clave se guarda en `orders.orders.idempotency_key` con índice único.
- Un reintento con la misma clave devuelve `orderId`, `saleId`, `orderNumber`, `saleNumber` y `total` originales.
- Una clave existente cuyo payload no coincide se rechaza con `409 IDEMPOTENCY_CONFLICT`.
- La unicidad de base de datos protege contra doble confirmación concurrente.

## Respuesta

Éxito: `201 Created`.

```json
{
  "orderId": "...",
  "saleId": "...",
  "orderNumber": "ORD-20260918-000001",
  "saleNumber": "SAL-20260918-000001",
  "total": 150.0000,
  "paid": 100.0000,
  "balance": 50.0000
}
```

## Errores

- `400 INVALID_REQUEST`: payload, cantidades, descuentos o importes inválidos.
- `401 UNAUTHORIZED`: JWT ausente o inválido.
- `403 FORBIDDEN`: falta `ORDER_CREATE` o se intenta override sin `ADMIN_ALL`.
- `404 NOT_FOUND`: cliente, producto, lista o precio inexistente.
- `409 CONFLICT`: idempotencia incompatible, cliente inactivo o suma de pagos superior al total.

## Auditoría y seguridad

- La operación requiere JWT.
- `ORDER_CREATE` permite confirmar sin overrides.
- `ADMIN_ALL` habilita overrides de precio/descuento.
- Cada confirmación exitosa registra `ORDER_CONFIRM` con orden, venta, total, pago y deuda.
- La auditoría ocurre dentro del flujo de negocio, y el rollback de la confirmación no deja una orden parcial.

## Pruebas

- Cálculo de precios, descuentos, redondeo y total.
- Resolución de lista explícita/asignada/GENERAL.
- Stock y movimientos `SALE` dentro de la misma transacción.
- Pago total, parcial, combinado y cuenta corriente.
- Rechazo de pago excedente y overrides sin permiso.
- Idempotencia secuencial y concurrente.
- Rollback completo cuando falla stock, precio o pago.
- Migración V6 y compatibilidad con seed existente.
- Smoke test real contra PostgreSQL en Docker Compose.
