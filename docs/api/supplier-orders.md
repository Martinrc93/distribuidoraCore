# Pedidos a proveedores

La sección de Operación aparece entre Ventas y Clientes. Menú, páginas y API
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

## Alcance

V35 crea `catalog.product_suppliers` y el schema `purchasing`. La solicitud se
registra y audita como `SUPPLIER_ORDER_CREATE`; no envía mensajes al proveedor,
registra pagos ni movimientos de stock. Recepción de mercadería, seguimiento de
entregas, facturas de compra y envío por mail/WhatsApp requieren un flujo separado.

Errores: `400` por datos inválidos, `401` sin sesión, `403` sin `ADMIN_ALL`,
`404` para proveedor/producto/pedido inexistente y `409` por reutilizar una clave
con otro contenido. El formulario conserva los datos ante errores y permite
reintentar; cada consulta de opciones e historial tiene reintento manual.
