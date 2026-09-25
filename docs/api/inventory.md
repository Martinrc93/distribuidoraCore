# Inventario

El inventario mantiene un único saldo por producto. No existen depósitos,
selección de ubicación ni transferencias de stock. La migración V24 suma los
saldos que existían por producto y elimina su atribución histórica a depósitos;
los movimientos, pedidos y ventas históricos se conservan.

## Consultar stock

```text
GET /api/inventory?page=0&size=20&search=
GET /api/inventory/{productId}/movements?page=0&size=20
```

Ambas lecturas requieren autenticación. El listado es paginado y permite buscar
por nombre; devuelve un único saldo por producto. Cada movimiento contiene
`id`, `movementType`, `quantity`, `reason`, `referenceType`, `referenceId` y
`date`, sin campos de ubicación.

## Ajustes manuales

`POST /api/inventory/{productId}/adjustments` requiere la autoridad
`STOCK_ADJUST` y responde `204 No Content`.

```json
{"quantity":2.5,"reason":"Conteo físico"}
```

`quantity` puede ser positivo o negativo, distinto de cero, y debe ser múltiplo
de `0.5`. El motivo admite hasta 500 caracteres. Se actualiza el saldo único del
producto y se registra un movimiento append-only junto con su evento de
auditoría.

El saldo negativo está permitido. Un ajuste inválido responde `400 Bad Request`;
un usuario sin `STOCK_ADJUST`, `403 Forbidden`; y una solicitud sin autenticación,
`401 Unauthorized`.

## Pedidos, devoluciones y cancelaciones

`POST /api/orders/confirm` no requiere ni contiene un campo de depósito. La
confirmación descuenta directamente del saldo único de cada producto. Editar un
pedido confirmado aplica los deltas al mismo saldo. Las devoluciones y
cancelaciones también lo restauran directamente, dentro de sus transacciones.

No existen los endpoints `/api/inventory/depots`,
`/api/inventory/depots/{depotId}/balances` ni
`/api/inventory/transfers`.

## Persistencia

- `inventory.inventory_balances` tiene una fila por producto, con `product_id`
  como clave primaria.
- `inventory.stock_movements` conserva el producto, tipo, delta, motivo,
  referencia y fecha; no tiene atribución a depósito.
- Pedidos y ventas no guardan depósito.
- La migración V24 consolida los balances V23 sumando `quantity` por producto y
  conserva la fecha de actualización más reciente. Mantiene los registros de
  movimientos, pedidos y ventas, sin su anterior atributo de depósito.
- Un reintento que cruce el despliegue de V24 con la clave de idempotencia de un
  pedido confirmado previamente puede responder `409`, porque su huella antigua
  incluía el depósito. El pedido original no se duplica; los reintentos iniciados
  bajo el contrato V24 conservan la idempotencia normal. Antes de iniciar una
  operación nueva tras ese `409`, verificar si el pedido original quedó registrado.
