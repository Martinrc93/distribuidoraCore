# Proyecciones de lectura comercial

Los endpoints de este documento alimentan deuda por cliente, detalle de venta,
intentos de entrega y auditoría. Las respuestas paginadas usan el contrato
`content`, `page`, `size`, `totalElements` y `totalPages`; la página comienza en
0 y el tamaño se limita a 100.

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
el formato numérico `dd/mm/aaaa` tanto en los filtros como en la tabla. Los filtros
se conservan en la URL y cambiar una fecha vuelve a la primera página. Vaciar
ambas permite consultar todo el historial.

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
