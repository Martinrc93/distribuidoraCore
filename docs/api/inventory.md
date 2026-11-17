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

Ambas lecturas requieren `ADMIN_ALL`. El listado es paginado y permite buscar
por nombre; devuelve un único saldo por producto. Cada movimiento contiene
`id`, `movementType`, `quantity`, `reason`, `referenceType`, `referenceId` y
`date`, sin campos de ubicación.

## Ajustes manuales

`POST /api/inventory/{productId}/adjustments` requiere la autoridad
`ADMIN_ALL` y `STOCK_ADJUST`, y responde `204 No Content`. El saldo es
compartido por toda la operación. V45 reserva este permiso al rol ADMIN y retira
las asignaciones previas a otros roles.

Se envía **uno** de estos dos campos (ambos o ninguno devuelve `400`):

```json
{"quantity":2.5,"reason":"Conteo físico"}
{"targetQuantity":24,"reason":"Conteo físico"}
```

- `quantity`: diferencia firmada, distinta de cero y múltiplo de `0.5`.
- `targetQuantity`: cantidad final contada, múltiplo de `0.5` (puede ser cero o
  negativa). El servidor bloquea el saldo, calcula la diferencia con el stock
  actual y la registra como movimiento `MANUAL_ADJUSTMENT`; el historial sigue
  siendo append-only. Si el stock ya es esa cantidad responde `409` sin cambios.
  La auditoría `STOCK_ADJUSTMENT` incluye `quantity` (diferencia),
  `previousQuantity` y `targetQuantity`.

`reason` es opcional y admite hasta 500 caracteres. Si se omite o está vacío se
guarda «Ajuste manual» (diferencia) o «Ajuste a cantidad contada» (cantidad
final), porque los movimientos conservan siempre un motivo. Se actualiza el saldo
único del producto y se registra un movimiento append-only junto con su evento de
auditoría.

El saldo negativo está permitido. Un ajuste inválido responde `400 Bad Request`;
un usuario sin alguna de las dos autoridades recibe `403 Forbidden`. Una solicitud sin autenticación recibe
`401 Unauthorized`.

## Mermas

Desde Stock se puede registrar la pérdida de un producto activo con
`POST /api/inventory/{productId}/shrinkages`. Requiere `ADMIN_ALL` y
`STOCK_SHRINKAGE`. V46 concede el permiso al rol ADMIN e invalida las sesiones
de los usuarios que lo reciben.

```json
{"idempotencyKey":"a553c441-4395-44e8-b3a4-fe18f6e2b623","quantity":2.5,"reason":"Envases dañados"}
```

La cantidad representa unidades perdidas. Debe ser positiva y múltiplo de
0,5. El motivo debe tener contenido y admite hasta 500 caracteres. El
servidor descuenta la cantidad del saldo global, crea un movimiento
`SHRINKAGE` con delta negativo y audita `STOCK_SHRINKAGE` con el ID del
movimiento. Las tres escrituras se confirman en la misma transacción.
El saldo puede quedar negativo, igual que con otros movimientos.

El éxito devuelve `204 No Content`. Un reintento con el mismo UUID, producto,
cantidad y motivo normalizado devuelve `204` sin repetir el descuento.
Reutilizar el UUID para otros datos o registrar una merma nueva de un producto
inactivo devuelve `409`. Un producto inexistente devuelve `404` y los datos
inválidos, `400`.

`GET /api/inventory/shrinkages?page=0&size=20&dateMin=2026-11-01&dateMax=2026-11-30`
requiere `ADMIN_ALL`. Devuelve `totalQuantity` sobre todas las filas del período
y `movements` con la página y el total de registros. Cada fila contiene
`id`, `productId`, `product` (nombre actual), `quantity` positiva, `reason`
y `date`. Ordena por fecha e ID descendentes. Los extremos ISO son opcionales
e incluyen todo el día de Argentina. Los rangos invertidos devuelven `400`.
El historial general del producto conserva la cantidad firmada del movimiento.

## Pedidos, devoluciones y cancelaciones

`POST /api/orders/confirm` no requiere ni contiene un campo de depósito. La
confirmación no modifica stock. `POST /api/orders/{id}/prepare` descuenta del saldo
único y registra la preparación; entregar un pendiente prepara en la misma
transacción. Editar un confirmado aplica deltas solo si ya está preparado.
Las cancelaciones revierten el efecto neto existente y las devoluciones de ventas
entregadas restauran las cantidades devueltas, dentro de sus transacciones.
Reactivar deja el pedido pendiente de preparación y no modifica stock.
Contrato completo: [preparación y revisión](order-fulfillment.md).

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
