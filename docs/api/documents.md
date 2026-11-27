# Documentos imprimibles

Los documentos se generan bajo demanda con OpenPDF y se devuelven como
`application/pdf` con `Content-Disposition: attachment`. El backend termina de
renderizar el PDF en un archivo temporal antes de enviar el contenido. Elimina el
archivo al finalizar la respuesta, incluso si falla la descarga. El frontend
permite imprimir o descargar con el componente compartido `DocumentActions`.

En equipos con Windows, el servicio local `scripts/print-agent/` (`127.0.0.1:47891`)
permite elegir en Administración → Configuración → Impresoras qué impresora
instalada usar automáticamente para documentos A4 y cuál para tickets de 80 mm.
La preferencia queda guardada en el navegador de esa PC (`distribuidora.printers`);
si el servicio local no está activo o se imprime desde un dispositivo táctil,
`DocumentActions` utiliza el diálogo de impresión del navegador como respaldo.

## Remito y ticket del pedido

- `GET /api/orders/{orderId}/documents/a4` y `.../documents/ticket` (80 mm):
  `ORDER_DOCUMENT_EXPORT` o `ADMIN_ALL`, con el alcance del vendedor. Ver
  [permisos comerciales](commerce-permissions.md) para la actualización desde ORDER_CREATE.

El encabezado del A4 y del ticket usa el logo, nombre, dirección y teléfono vigentes en
Administración → Configuración → Datos del comercio. El logo opcional aparece centrado
arriba, manteniendo sus proporciones. Si no hay logo, se omite el espacio de la imagen.
Los campos de contacto vacíos se omiten.
`GET` y `PUT /api/settings/business-profile` requieren `ADMIN_ALL` y devuelven
`name`, `address` y `phone`. Ambos muestran el vendedor asignado al pedido
y la fecha de Buenos Aires en formato `dd/MM/aaaa`, sin hora.

`GET /api/settings/business-logo` devuelve `{ "content": null }` sin logo o el PNG
guardado en Base64 para usuarios autenticados. `PUT` recibe `{ "content": "<Base64>" }`
con un PNG o JPG de hasta 512 KB y 2048 × 2048 píxeles. La API valida el archivo y lo
guarda como PNG de hasta 384 píxeles por lado, sin deformarlo y conservando transparencia.
`DELETE` lo quita y devuelve `204`. Guardar y quitar requiere `ADMIN_ALL`.

Cada producto muestra la cantidad por precio unitario y el importe a la derecha.
Si tiene descuento, muestra el porcentaje, el importe descontado y el «Importe
C/Desc». El resumen muestra la suma de cantidades, el subtotal bruto y los
descuentos por producto. Debajo de «Descuentos» aparece el descuento general si
corresponde. «Pagado» y «Pendiente» aparecen cuando hay un pago registrado.
Los importes usan formato argentino con dos decimales. El A4 organiza los
productos en columnas, repite sus encabezados en cada página e indica
«Página X de Y» al pie. El ticket
ajusta el alto del papel al contenido.

## Lista de precios

`GET /api/documents/price-lists/{listId}?categoryId=&brandId=&search=`

- Requiere `ORDER_CREATE` o `ADMIN_ALL`: los vendedores pueden descargarla para
  consultarla sin conexión.
- A4 con los precios vigentes hoy (fecha de Buenos Aires) de los productos activos,
  con categorías (rubros) en orden alfabético y productos en orden alfabético
  dentro de cada categoría. Nunca incluye el costo.
- Los filtros opcionales son los mismos de la tabla de precios y se describen en el
  encabezado. Una lista, categoría o marca inexistente devuelve `404`.

`GET /api/documents/price-lists`

- Requiere `ADMIN_ALL`.
- Devuelve un solo PDF A4 con todas las listas activas. Cada lista
  comienza en una página nueva y usa el mismo orden de categorías y productos que
  la descarga individual.

## Estado de deuda del cliente

`GET /api/documents/customers/{customerId}/statement?format=a4|ticket`

- Requiere `CUSTOMER_STATEMENT_EXPORT` o `ADMIN_ALL`, independientemente de
  `CUSTOMER_VIEW` y `SALE_PAYMENT`. V44 conserva el acceso previo a documentos
  concediendo el permiso a los roles existentes que tenían `SALE_PAYMENT`.
  Un vendedor solo ve clientes asignados y
  ventas de sus pedidos (o de pedidos sin vendedor de sus clientes), igual que
  `GET /api/customers/{customerId}/debts`.
- Lista las ventas `CONFIRMED`/`DELIVERED` con saldo pendiente, de la más antigua a
  la más nueva: fecha, venta, pedido, total, pagado y saldo (saldo contable limitado
  a `total - paid`), y el saldo total pendiente.
- `format=a4` (por defecto) o `ticket` (80 mm). Otro valor devuelve `400`; un cliente
  inexistente, `404`.
- El documento admite hasta 5.000 ventas pendientes. Si la cuenta supera ese
  límite, devuelve `400`. El formato ticket divide el contenido largo en páginas.

## Compras del cliente por período

`GET /api/documents/customers/{customerId}/purchases?dateMin=&dateMax=`

Requiere `REPORTS_VIEW` o `ADMIN_ALL`. El PDF A4 incluye el total y todo el
detalle por pedido y producto del período autorizado. Usa el mismo alcance,
fechas e importes que el [reporte de compras del cliente](customer-purchases.md).
La exportación admite hasta 2.000 ventas y 25.000 filas de detalle. Si el período
supera algún límite, devuelve `400` y solicita un rango más corto.

Desde la Fase 9.2, el estado de deuda de un revendedor incluye todas las ventas a
cargo de su cuenta y agrega la columna «Cliente» (cliente que recibió cada pedido);
el ticket la muestra como línea «Cliente …».
