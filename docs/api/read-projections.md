# Proyecciones de lectura comercial

Los endpoints de este documento alimentan deuda por cliente, detalle de venta,
intentos de entrega y auditoría. Las respuestas paginadas usan el contrato
`content`, `page`, `size`, `totalElements` y `totalPages`; la página comienza en
0 y el tamaño se limita a 100.

## Totales de Resumen

`GET /api/dashboard?dateMin=2026-09-01&dateMax=2026-09-30` requiere `ADMIN_ALL`.
`totals` y cada fila de `bySeller` incluyen `cashPaid` y `transferPaid`, además
de `totalPaid`: suman los pagos `CASH` y `BANK_TRANSFER` asociados a las ventas
no canceladas de los pedidos del período. Son cobros acumulados al consultar,
incluidos los posteriores al rango de creación, y no dependen de la entrega.

## Pedidos entregados por vendedor en Resumen

`GET /api/dashboard/seller-orders?sellerId=<uuid>&dateMin=2026-09-01&dateMax=2026-09-30&page=0&size=20`

Requiere `ADMIN_ALL`. Para la fila «Sin asignar», omitir `sellerId` y enviar
`unassigned=true`; combinar ambos o no indicar ninguno devuelve 400. Las fechas
ISO seleccionan los pedidos por creación, incluyendo ambos días completos de
Argentina; fechas omitidas usan hoy y un rango invertido devuelve 400.
Solo incluye pedidos actualmente entregados con ventas no canceladas.
La respuesta paginada contiene `id`, `number`, `customer`, `date`, `deliveredAt`
(puede ser null en registros históricos), `saleId`, `total`, `paid`,
`accountBalance` y `payments` (`method`, `amount`, agrupados por método).
El saldo contable tiene el mismo cálculo que Resumen: deuda de la venta según
el libro, limitada al importe impago, sin incorporar deuda de otras ventas.
Los cobros acumulados incluyen pagos posteriores al rango de creación.

## Listado de clientes con filtros

```http
GET /api/customers?page=0&size=20&search=&sellerId=<uuid>&hasBalance=true&status=ACTIVE
```

`search` busca por nombre o identificación; `sellerId` filtra por vendedor
asignado. `hasBalance=true` incluye saldos de cuenta corriente distintos de cero,
tanto deuda como saldo a favor. `status` admite `ACTIVE`, `INACTIVE` o vacío para
todos. Omitir estos filtros conserva la consulta completa para otros consumidores;
la pantalla de clientes envía `ACTIVE` por defecto y ofrece Activo/Inactivo/Todos.
Un vendedor autenticado sigue limitado a sus clientes, incluso con otro
`sellerId`. Todos los filtros se combinan antes de paginar y calcular los totales.
Un UUID, booleano o estado inválido devuelve `400`.

`GET /api/customers/filter-options` devuelve `sellers` con opciones `{ id, name }`
distintas, ordenadas por nombre e ID, de vendedores con clientes visibles.
No limita las opciones a una página ni al estado activo. La pantalla conserva
los filtros en la URL y vuelve a la primera página al cambiarlos.

## Listado de pedidos por fecha

```http
GET /api/orders?page=0&size=20&search=&status=&dateMin=2026-09-01&dateMax=2026-09-30
```

`dateMin` y `dateMax` son opcionales, con formato ISO `yyyy-MM-dd`. Filtran
la fecha de creación del pedido por días completos en
`America/Argentina/Buenos_Aires`, incluyendo ambos extremos. El filtro se aplica
antes de paginar y también a `totalElements` y `totalPages`, junto con búsqueda,
estado y el alcance del vendedor. Omitir o vaciar un extremo elimina ese límite.
Una fecha inválida o un rango invertido devuelve `400`.

La pantalla de pedidos muestra ambas fechas inicialmente con el día actual y usa
el formato numérico `dd/mm/aaaa` tanto en los filtros como en la tabla. Un clic en
el campo o en su botón abre un calendario con meses, días y acciones en español,
independiente del idioma del navegador; también admite edición manual.
Los filtros
se conservan en la URL y cambiar una fecha vuelve a la primera página. Vaciar
ambas permite consultar todo el historial.

## Creación de pedidos en la interfaz

`GET /api/pricing/resolve-batch?customerId=<uuid>&priceListId=<uuid>&productIds=<uuid>,<uuid>`
devuelve un arreglo con `productId`, `priceListId`, `priceListCode` y `unitPrice`.
Admite entre 1 y 100 IDs y elimina duplicados. Requiere `ORDER_CREATE` o
`ADMIN_ALL` y respeta el alcance de clientes del vendedor. Usa el precio vigente
en Argentina de la lista elegida; si falta, aplica las mismas listas anteriores
activas y el mismo orden que la resolución individual. Los productos sin precio
se omiten y la UI bloquea agregarlos. La confirmación conserva la resolución
autoritativa del servidor. La UI precarga solamente los productos disponibles
para el pedido y reutiliza el resultado al seleccionar y agregar líneas.

En la creación de pedidos, la UI selecciona automáticamente `priceListId` del
cliente; si es nulo, usa la lista activa `GENERAL`, conforme al criterio de
pricing. La confirmación envía `payments: []`: el pedido se registra sin cobro
inicial y su importe queda pendiente en cuenta corriente. El resumen de totales
y el botón de confirmación aparecen debajo de los productos.

La confirmación admite `previousBalanceAmount` opcional (cero por defecto),
no negativo, de hasta cuatro decimales y limitado al saldo deudor actual del
cliente, comprobado bajo bloqueo transaccional. Se guarda como
`orders.orders.previous_balance_amount` para incluirlo en el futuro remito.
Es un concepto de cobro separado de los productos: no se descuenta, no mueve
stock y no vuelve a generar deuda ni un pago. `total`, la venta y el débito en
cuenta corriente siguen correspondiendo solo al nuevo pedido. La respuesta de
confirmación y el detalle incluyen `previousBalanceAmount` y `collectionTotal`
(total del pedido más el importe seleccionado). Cambiar este importe con la
misma clave de idempotencia produce un conflicto; omitirlo o enviar cero conserva
la compatibilidad de las claves anteriores. La carga del pedido anterior no lo copia.

`PUT /api/orders/{orderId}` requiere `ADMIN_ALL` y un pedido y venta confirmados.
Recibe `priceListId`, `lines` (con `unitPriceOverride` opcional por producto),
`orderDiscountPercent` y `previousBalanceAmount` opcional. Conserva cliente,
vendedor y pagos existentes. Omitir `previousBalanceAmount` conserva el importe
guardado; enviar cero lo elimina. Un importe nuevo debe ser no negativo, tener
hasta cuatro decimales y no superar el saldo deudor actual del cliente. No genera
deuda, pago ni movimiento de stock por ese concepto. La edición recalcula productos,
ajusta solo sus diferencias de stock y deuda y rechaza un total menor al ya cobrado.

`GET /api/customers/{customerId}/last-order` requiere `ORDER_CREATE` o `ADMIN_ALL`
y comprueba acceso al cliente y al pedido según el alcance del vendedor. Devuelve
`{ "available": false }` si no hay pedidos confirmados o entregados visibles.
Selecciona el último por fecha de creación y, en empate, ID descendente.
Cuando existe, devuelve `available`, `orderId`, `orderNumber`,
`orderDiscountPercent` e `items` con `productId`, `productName`,
`presentation`, `status`, `quantity` y `lineDiscountPercent`. Los datos
del producto corresponden al catálogo actual; el nombre histórico sirve de
respaldo si ya no existe. No se limita a las primeras páginas del catálogo.
La UI reemplaza los productos del borrador con sus cantidades y resuelve los
precios de la lista actual, sin copiar precios manuales ni cobros. Solo el
administrador puede copiar descuentos, tras elegirlo en un modal que aparece
cuando el pedido anterior los contiene. Cargar sin descuentos los restablece
a cero. Los productos no activos bloquean la carga.

Los productos se identifican por UUID; los contratos de catálogo, precios y
descuentos no contienen SKU. La migración V31 elimina la columna del catálogo
conservando productos, precios y referencias históricas. Las migraciones
anteriores mantienen sus checksums originales.
`GET /api/products` admite `includeStock` (por defecto `true`). Con `false`,
omite el campo `stock` y la consulta a `inventory.inventory_balances`. La creación
de pedidos usa esta variante en todas las páginas; la gestión de inventario
conserva la lectura de stock.

## Deudas abiertas de un cliente

```http
GET /api/customers/{customerId}/debts?page=0&size=20
```

Requiere `SALE_PAYMENT` o `ADMIN_ALL`. Los usuarios seller quedan limitados a
clientes asignados. Devuelve ventas `CONFIRMED` o `DELIVERED` con saldo positivo,
ordenadas de más antigua a más nueva. Cada elemento contiene `saleId`,
`saleNumber`, `status`, `total`, `paid`, `orderId`, `orderNumber`, `createdAt` y
`balance`. El saldo considera débitos y créditos del ledger y se limita al saldo
pendiente de la venta.

La UI usa `saleId` para imputar el cobro a esa venta; sin selección conserva la
imputación FIFO. Un usuario sin permiso recibe `403`.

## Listado de ventas con filtros independientes

```http
GET /api/sales?page=0&size=20&search=SAL-001&customerId=<uuid>&sellerId=<uuid>&pendingBalance=true&dateMin=2026-09-01&dateMax=2026-09-30
```

`search` busca exclusivamente por número de venta, de forma parcial y sin
distinguir mayúsculas. `customerId` y `sellerId` son UUID opcionales y filtran
por identidad exacta. El vendedor es el del pedido; si no tiene uno asignado,
se usa el vendedor del cliente. Los filtros se combinan entre sí.

`dateMin` y `dateMax` son opcionales en formato ISO `yyyy-MM-dd`. Filtran la
fecha de creación de la venta por días completos de
`America/Argentina/Buenos_Aires`, incluyendo ambos extremos. Omitir o vaciar
un extremo elimina ese límite. Un UUID o fecha inválidos, o un rango invertido,
devuelven `400`.

`pendingBalance` es opcional y vale `false` por defecto. Con `true` incluye solo
ventas con `total > paid`, excluyendo las canceladas. Incluye ventas sin pagos
y con pagos parciales. Los filtros se aplican antes de paginar y al conteo total;
conservan el alcance del vendedor autenticado.

`GET /api/sales/filter-options` devuelve `customers` y `sellers`, cada uno con
opciones `{ id, name }` distintas y ordenadas por nombre. Incluye las entidades
con ventas visibles para el usuario, sin limitar las opciones a una página de
resultados. Un vendedor solo recibe opciones dentro de su alcance.

La pantalla utiliza desplegables de cliente y vendedor con búsqueda local por
nombre (ignora mayúsculas y acentos). Elegir una opción aplica su ID; escribir
solo reduce las opciones disponibles. Conserva los filtros
en la URL y vuelve a la primera página al cambiar cualquiera. Reutiliza los
campos de fecha y calendario de pedidos, con formato `dd/mm/aaaa`; ambos
inician vacíos para conservar la consulta de todo el historial. En móvil los
filtros principales ocupan filas completas y las fechas forman dos columnas.

## Detalle de venta y líneas retornables

```http
GET /api/sales/{saleId}
```

Devuelve el detalle comercial asociado al pedido (`order`, `items`, `sale`,
`payments`, `account`, `deliveryAttempts`) más `saleItems`. Cada línea contiene
`saleItemId`, `productId`, `productName`, `quantity`, `returnedQuantity` y
`returnableQuantity`. La autorización seller se comprueba contra el pedido
asociado.

La UI usa `saleItemId` al llamar `POST /api/sales/{saleId}/returns` y limita la
cantidad a `returnableQuantity`, en múltiplos de 0.5. La devolución repone stock
en el depósito original y no reembolsa pagos ni acredita la cuenta corriente.
El endpoint de comando y sus errores están descritos en
[`sale-returns.md`](sale-returns.md).

## Historial de entrega

```http
GET /api/orders/{orderId}
```

El detalle existente incluye `deliveryAttempts`, ordenados por `attemptNumber`.
Cada intento informa `id`, `attemptNumber`, `result`, `observation`,
`attemptedBy` y `attemptedAt`, además de los snapshots, pagos y cuenta de la
venta. Los sellers solo pueden leer pedidos dentro de su alcance.

## Lectura de auditoría

```http
GET /api/audit?page=0&size=20&search=DELIVERY_ATTEMPT
```

Requiere `ADMIN_ALL`; el resto recibe `403`. El texto de búsqueda se aplica sin
distinguir mayúsculas a operación, tipo/ID de recurso y resultado. Los
elementos contienen `id`, `actorUserId`, `actor`, `operation`, `resourceType`,
`resourceId`, `result`, `correlationId`, `details` y `createdAt`, ordenados del
más reciente al más antiguo. `details` se entrega como texto JSON de la columna
append-only.

La pantalla `/admin/audit` guarda `search` y `page` en la URL y muestra actor,
operación, recurso, resultado y request ID. Las rutas y los comandos siguen
autorizados por el backend.
