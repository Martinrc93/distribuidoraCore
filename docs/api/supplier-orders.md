# Pedidos a proveedores

En la interfaz la sección se llama «Compras» (antes «Pedidos a proveedores») y aparece en Operación, entre Ventas y Clientes. La API y las rutas conservan `supplier-orders`. Menú, páginas y API
requieren `ADMIN_ALL`, igual que la administración de proveedores y productos.

## Proveedores opcionales por producto

`POST /api/products` y `PUT /api/products/{id}` aceptan `supplierIds`, una lista
opcional de UUID de proveedores existentes. La asociación permite varios
proveedores por producto y varios productos por proveedor. Los IDs repetidos o
inexistentes se rechazan. Omitir el campo conserva las asociaciones al editar;
enviar `[]` las elimina. Crear sin este campo deja el producto sin proveedores.

Las lecturas administrativas de productos incluyen `suppliers: [{id, name}]`.
La selección carga todas las páginas autorizadas, excluye los ya elegidos y
permite quitar incluso el último proveedor. Estas asociaciones son informativas:
se pueden solicitar todos los productos activos, también los no asociados.

## Registrar una solicitud

`POST /api/supplier-orders` devuelve `201` con `{id, number, total}`.

```json
{
  "supplierId": "<UUID>",
  "orderDate": "2026-10-08",
  "idempotencyKey": "<UUID del intento>",
  "lines": [{"productId": "<UUID>", "quantity": 3, "unitCost": 25.5}]
}
```

Se exige un proveedor existente, fecha y entre 1 y 1000 productos activos sin
repetir. La cantidad debe ser positiva y el costo no negativo, con hasta cuatro
decimales. Omitir `unitCost` usa el costo actual del producto. Los subtotales se
redondean a cuatro decimales con `HALF_UP`; el backend calcula el total.
El pedido conserva el nombre del proveedor, los nombres de productos, cantidades
y costos al solicitarlo. Cambios posteriores no recalculan el historial.

Cada intento tiene una clave única. Repetir clave y contenido devuelve el pedido
existente; cambiar su contenido con la misma clave devuelve `409`. El frontend
conserva clave y contenido para reintentar, y crea una clave nueva al cambiar el
borrador. La numeración `PRV-00000001` usa una secuencia; puede tener saltos.

## Historial y último pedido

- `GET /api/supplier-orders?page=0&size=20&supplierId=<UUID>&dateMin=2026-09-01&dateMax=2026-09-30`
- `GET /api/supplier-orders/{id}`
- `GET /api/supplier-orders/supplier/{supplierId}/last-order`

Proveedor y fechas son opcionales. El rango es inclusivo y se filtra antes de
paginar, por `orderDate`. Sin fechas se muestra todo el historial. Se conserva
el filtro en la URL y al volver del detalle. El orden es fecha del pedido,
fecha de registro e ID descendentes.

El último pedido usa el ID exacto del proveedor y todo su historial, sin depender
del calendario del listado. Devuelve `{available: false}` si no hay pedidos;
si existe devuelve `order`, `items` y `available: true`. Cada item incluye
`status` y `currentCost` además de su snapshot histórico. El formulario copia
cantidades con costos actuales; productos inactivos impiden la copia. Cargar
el anterior requiere confirmación si reemplazará productos del borrador.

## Estados y stock (V40)

| Estado | Significado | Stock |
|---|---|---|
| `DRAFT` (Borrador) | Compra registrada sin confirmar | No lo mueve |
| `CONFIRMED` (Confirmada) | Mercadería ingresada | Suma las cantidades (`PURCHASE`) |
| `CANCELLED` (Anulada) | No puede modificarse | Si estaba confirmada, descuenta (`PURCHASE_CANCELLATION`) |
| `LEGACY` (Histórica) | Registrada antes de V40, cuando las compras no movían stock | Nunca lo mueve: editarla o anularla no cambia el stock (evita contar dos veces mercadería ya ajustada a mano) |

- `POST /api/supplier-orders` acepta `confirm: true` para registrar y confirmar en
  una sola transacción; sin ese campo queda `DRAFT`. La respuesta incluye `status`.
  `confirm` forma parte de la huella de idempotencia.
- `POST /api/supplier-orders/{id}/confirm`: solo `DRAFT` → `CONFIRMED`; registra un
  movimiento `PURCHASE` por línea con referencia a la compra.
- `PUT /api/supplier-orders/{id}` con `supplierId`, `orderDate` y `lines` (sin clave
  de idempotencia) reemplaza proveedor, fecha y líneas. En una compra `CONFIRMED`
  registra `PURCHASE_ADJUSTMENT` por producto con la diferencia (positiva o
  negativa, incluidas líneas agregadas o quitadas). Una compra anulada devuelve `409`.
- `POST /api/supplier-orders/{id}/cancel`: anula cualquier estado salvo `CANCELLED`.
- Las cantidades de compras nuevas y editadas (no históricas) deben ser múltiplos
  de `0.5`, como el stock. Las operaciones bloquean la compra (`for update`) y los
  saldos de stock; se auditan como `SUPPLIER_ORDER_CREATE`, `_CONFIRM`, `_UPDATE`
  (con los cambios de stock) y `_CANCEL`.
- Listado y detalle incluyen `status`; el detalle agrega `confirmedAt` y `cancelledAt`.

## Alcance

Proveedores y compras son recursos globales. Listado, detalle, historial,
alta, edición, confirmación y anulación requieren `ADMIN_ALL`. Los permisos
de pedidos de clientes no dan acceso a compras ni a sus movimientos de stock.

V35 crea `catalog.product_suppliers` y el schema `purchasing`. No envía mensajes
al proveedor ni registra pagos o facturas de compra; el envío por mail/WhatsApp
requiere un flujo separado.

## Reposición

La acción «Reponer» de Stock está disponible en productos activos con saldo
cero o negativo. También está disponible en las filas de Cobertura estimada
de stock del Dashboard. Abre el formulario de compra con el producto, costo
y saldo leídos del catálogo al abrir. Si el producto está inactivo o ya no está
en el catálogo, el formulario muestra el aviso y ofrece volver al origen.

Sugiere cubrir el saldo negativo más una unidad, redondeando hacia arriba al
próximo múltiplo de 0,5. Si el saldo ya es positivo, sugiere una unidad. Completa
el proveedor si hay una única asociación disponible. Con varias, muestra sus
nombres y permite elegir. El selector conserva el catálogo de proveedores con
todas sus páginas. Cantidad y costo son editables antes de guardar.

«Agregar productos sin stock» sigue disponible en Compras y usa la misma
sugerencia. Agrega productos activos con saldo menor o igual a cero. Si el
proveedor tiene asociaciones, se limita a sus productos.

La reposición guarda mediante el mismo `POST /api/supplier-orders`. Abrir el
formulario prepara el borrador local. Guardar como borrador lo registra sin
mover stock. Confirmar la compra ingresa las cantidades.

Errores: `400` por datos inválidos, `401` sin sesión, `403` sin `ADMIN_ALL`,
`404` para proveedor/producto/pedido inexistente y `409` por reutilizar una clave
con otro contenido. El formulario conserva los datos ante errores y permite
reintentar; cada consulta de opciones e historial tiene reintento manual.
