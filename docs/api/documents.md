# Documentos imprimibles

Todos los documentos se generan bajo demanda con OpenPDF, no se guardan y se
devuelven como `application/pdf` con `Content-Disposition: attachment`. El
frontend los imprime sin descargarlos (iframe oculto y diálogo de impresión) o los
descarga, con el componente compartido `DocumentActions`.

## Remito y ticket del pedido

- `GET /api/orders/{orderId}/documents/a4` y `.../documents/ticket` (80 mm):
  `ORDER_CREATE` o `ADMIN_ALL`, con el alcance del vendedor. Ver ADR-008.

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

- Requiere `SALE_PAYMENT` o `ADMIN_ALL`. Un vendedor solo ve clientes asignados y
  ventas de sus pedidos (o de pedidos sin vendedor de sus clientes), igual que
  `GET /api/customers/{customerId}/debts`.
- Lista las ventas `CONFIRMED`/`DELIVERED` con saldo pendiente, de la más antigua a
  la más nueva: fecha, venta, pedido, total, pagado y saldo (saldo contable limitado
  a `total - paid`), y el saldo total pendiente.
- `format=a4` (por defecto) o `ticket` (80 mm). Otro valor devuelve `400`; un cliente
  inexistente, `404`.
