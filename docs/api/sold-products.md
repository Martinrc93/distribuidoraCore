# Historial de artículos vendidos

El reporte de 7.1 se abre desde Ventas y desde «Productos más vendidos» en
Dashboard. Los endpoints requieren `REPORTS_VIEW` o `ADMIN_ALL`.
La migración V47 concede `REPORTS_VIEW` inicialmente a ADMIN. Un administrador
puede habilitarlo para un rol asignado desde Roles y permisos.

- `ADMIN_ALL` consulta ventas confirmadas y entregadas de toda la operación.
- Un rol con alcance asignado consulta solo ventas entregadas de sus pedidos.
  Si un pedido no tiene vendedor, se usa la asignación actual del cliente.
  Se requiere un perfil de vendedor activo.

Los filtros de autorización y período se aplican antes de contar o agregar.
Las ventas canceladas quedan fuera de ambos alcances.

## Listado por artículo

`GET /api/reports/sold-products?page=0&size=20&dateMin=2026-10-01&dateMax=2026-10-31&search=harina`

Devuelve una página estándar con `content`, `page`, `size`, `totalElements`
y `totalPages`. El tamaño máximo es 100. `search` busca un texto literal sin
distinguir mayúsculas en el nombre actual del producto. Se puede filtrar
también por `productId` exacto. Ordena por nombre e ID. Cada fila agrupa las
líneas por ID de producto y devuelve `productId`, `product`, `salesCount`,
`soldUnits`, `returnedUnits`, `netUnits` y `amount`.

## Ventas de un artículo

`GET /api/reports/sold-products/{productId}/sales?page=0&size=20&dateMin=2026-10-01&dateMax=2026-10-31`

Devuelve `product` con los totales de todo el período y `sales` con las
ventas paginadas, más recientes primero. Cada venta trae `id`, `orderId`,
`number`, `customer`, `status`, `date` y las mismas cantidades e importe del
artículo en esa venta. La interfaz permite abrir el pedido correspondiente.
Sin ventas visibles, `product` es nulo y la página queda vacía, incluso al
consultar por ID de un producto cuya actividad pertenece a otro vendedor.

## Fechas y cálculo

- Las fechas ISO delimitan días completos inclusivos de Buenos Aires y filtran
  por `sale.sales.created_at`. Si se omiten, se usa el primer día del mes y hoy.
  Un rango invertido, futuro, anterior a 1900 o mayor a 366 días devuelve `400`.
- Las unidades devueltas se suman por línea de venta, aunque la devolución se
  haya registrado después del rango. Las unidades netas descuentan las devueltas
  de cada línea, con piso cero. Los totales reflejan la información vigente.
- `amount` representa el importe vendido en ARS. Se reparte el total persistido
  de cada venta proporcionalmente a los subtotales de sus líneas después del
  descuento de producto. Esto incluye el descuento general y conserva el total
  de las ventas históricas. El redondeo acumulado a cuatro decimales distribuye
  los residuos en orden estable de ID de línea. Se hace el reparto antes de
  filtrar el producto. Las líneas de una venta con subtotal cero aportan cero.
- Las devoluciones de mercadería cambian las cantidades, sin imputar reintegros
  ni créditos al importe. El reporte consulta el total y los movimientos ya
  registrados sin modificar ventas, stock ni cuenta corriente.

Cada respuesta obtiene conteos, totales y filas en una transacción de lectura
consistente.
