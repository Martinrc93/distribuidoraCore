# Compras del cliente por período

La ficha del cliente enlaza a «Compras del cliente». El reporte y el PDF
requieren `REPORTS_VIEW` o `ADMIN_ALL`. Abrir la ficha requiere
`CUSTOMER_VIEW` o `ADMIN_ALL`. Los dos endpoints del reporte pueden
consultarse por separado de la ficha y del estado de deuda.

- El administrador consulta ventas confirmadas y entregadas del cliente.
- Un rol asignado necesita un perfil activo y acceso al cliente. Solo ve
  ventas entregadas de sus pedidos. Si un pedido carece de vendedor, se
  usa la asignación actual del cliente.
- Un cliente inexistente o fuera del alcance devuelve `404`. La falta del
  permiso de reportes devuelve `403`.

## Resumen y detalle

`GET /api/reports/customers/{customerId}/purchases?dateMin=2026-10-01&dateMax=2026-10-31&page=0&size=20`

- `summary` trae `customerId`, `customer`, `dateMin`, `dateMax`,
  `salesCount` y `totalAmount`. El conteo y el importe cubren todo el
  período autorizado, incluso al cambiar de página.
- `purchases` es una página de ventas de hasta 100 entradas, de la más reciente
  a la más antigua. Cada venta trae `id`, `orderId`, `saleNumber`,
  `orderNumber`, `date`, `status`, `amount` e `items`. Cada línea trae
  `id`, `product` (nombre guardado en la venta), `soldUnits`,
  `returnedUnits`, `netUnits` y `amount`.
- Un cliente accesible sin ventas en el período devuelve conteo e importe cero
  y una página vacía.

## Impresión y descarga

`GET /api/documents/customers/{customerId}/purchases?dateMin=2026-10-01&dateMax=2026-10-31`

Devuelve un PDF A4 con el total del período y todos los pedidos y líneas
visibles. Los encabezados se repiten si el detalle de un pedido ocupa más de
una página del PDF.

## Fechas e importes

Se aplican las mismas [reglas de fechas y cálculo](sold-products.md#fechas-y-cálculo)
que en el historial de artículos vendidos. Las fechas delimitan días completos
inclusivos de Buenos Aires según la fecha de creación de la venta. El rango
por defecto va del primer día del mes a hoy y admite hasta 366 días.

El total suma los importes persistidos en ARS después de los descuentos. Las
líneas reciben la parte proporcional del total de cada venta. Las devoluciones
actualizan las unidades devueltas y netas.
