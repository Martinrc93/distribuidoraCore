# Documentos imprimibles

Todos los documentos se generan bajo demanda con OpenPDF, no se guardan y se
devuelven como `application/pdf` con `Content-Disposition: attachment`. El
frontend los imprime sin descargarlos (iframe oculto y diálogo de impresión) o los
descarga, con el componente compartido `DocumentActions`.

## Remito y ticket del pedido

- `GET /api/orders/{orderId}/documents/a4` y `.../documents/ticket` (80 mm):
  `ORDER_DOCUMENT_EXPORT` o `ADMIN_ALL`, con el alcance del vendedor. Ver
  [permisos comerciales](commerce-permissions.md) para la actualización desde ORDER_CREATE.

## Lista de precios

`GET /api/documents/price-lists/{listId}?categoryId=&brandId=&search=`

- Requiere `ORDER_CREATE` o `ADMIN_ALL`: los vendedores pueden descargarla para
  consultarla sin conexión.
- A4 con los precios vigentes hoy (fecha de Buenos Aires) de los productos activos,
  agrupados por categoría (rubro) y ordenados por nombre. Nunca incluye el costo.
- Los filtros opcionales son los mismos de la tabla de precios y se describen en el
  encabezado. Una lista, categoría o marca inexistente devuelve `404`.

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

## Compras del cliente por período

`GET /api/documents/customers/{customerId}/purchases?dateMin=&dateMax=`

Requiere `REPORTS_VIEW` o `ADMIN_ALL`. El PDF A4 incluye el total y todo el
detalle por pedido y producto del período autorizado. Usa el mismo alcance,
fechas e importes que el [reporte de compras del cliente](customer-purchases.md).

Desde la Fase 9.2, el estado de deuda de un revendedor incluye todas las ventas a
cargo de su cuenta y agrega la columna «Cliente» (cliente que recibió cada pedido);
el ticket la muestra como línea «Cliente …».
