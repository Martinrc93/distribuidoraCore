# Configuración de comisiones

En Administración → Configuración → Comisiones, un administrador puede guardar
planes y asignar el mismo plan a varios vendedores y revendedores. Debajo del
formulario se muestran los beneficiarios con un plan asignado. La opción
«Sin comisión» quita la asignación elegida. V49 crea las tablas de configuración
sin asignaciones iniciales; V53 agrega las de revendedores.

Un plan contiene una o más reglas. Cada regla guarda un importe fijo en ARS
(`FIXED`) o un porcentaje entre 0 y 100 (`PERCENT`). Admite una regla general
opcional y una regla por categoría, marca o artículo. El cero es un valor
configurable. La configuración se limita a comisiones comerciales. El sueldo
fijo del empleado queda fuera de estos planes.

## API

Todos los endpoints requieren `ADMIN_ALL`.

| Operación | Endpoint | Resultado |
| --- | --- | --- |
| Listar planes | `GET /api/settings/commission-plans` | ID, nombre, cantidad de reglas, de vendedores (`sellerCount`) y de revendedores (`resellerCount`) asignados. |
| Leer plan | `GET /api/settings/commission-plans/{id}` | Plan con versión y reglas. |
| Crear plan | `POST /api/settings/commission-plans` | Plan guardado con versión inicial 1. |
| Editar plan | `PUT /api/settings/commission-plans/{id}` | Plan actualizado en una sola transacción. |
| Listar planes asignados | `GET /api/settings/commission-plans/assignments?page=0&size=20` | Página con tipo (`SELLER` o `RESELLER`), ID, nombre y estado del beneficiario, e ID y nombre del plan. Ordenada por nombre. |
| Consultar asignación | `GET /api/settings/commission-plans/assignments/{sellerId}` | `sellerId` y `planId`, que puede ser nulo. |
| Cambiar asignación | `PUT /api/settings/commission-plans/assignments/{sellerId}` | Asignación actualizada. Enviar `planId: null` para quitar el plan. |
| Consultar plan de un revendedor | `GET /api/settings/commission-plans/reseller-assignments/{customerId}` | `customerId` y `planId`, que puede ser nulo. |
| Cambiar plan de un revendedor | `PUT /api/settings/commission-plans/reseller-assignments/{customerId}` | Igual que la de vendedores. Solo acepta planes para clientes `RESELLER`; si no, `409`. |

Al crear o editar, enviar `name` y `rules`. Cada regla contiene `scope`
(`DEFAULT`, `CATEGORY`, `BRAND` o `PRODUCT`), `kind` y `value`. Las reglas
específicas requieren el `targetId` del catálogo. Una regla general usa
`targetId: null`. Los valores viajan como cadenas decimales con punto y hasta
cuatro decimales para conservar la precisión.

Al editar, enviar la `version` leída. Para cambiar una asignación, enviar
también `expectedPlanId` con el plan leído o `null` si no había plan. Las
ediciones desactualizadas devuelven `409`. Los cambios de planes y de
asignaciones quedan en auditoría (`SELLER_COMMISSION_ASSIGNMENT` y
`RESELLER_COMMISSION_ASSIGNMENT`).

## Entrega y liquidación

V50 registra la comisión pendiente dentro de la transacción que marca el
pedido como entregado. El devengamiento ocurre aunque la venta tenga saldo por
cobrar. Usa el vendedor activo del pedido o, si falta, el vendedor activo
asignado al cliente. Se aplica el plan asignado en ese momento, en orden de
prioridad artículo, marca, categoría y regla general.

La entrega guarda el plan, su versión, el valor de la regla, el costo unitario
vigente y el importe de la línea después de prorratear los descuentos. El
porcentaje se calcula sobre la ganancia positiva. El fijo se multiplica por
las unidades. Se redondea a cuatro decimales. Una devolución posterior descuenta
la parte proporcional de la comisión original. Si ya estaba liquidada, el
ajuste queda en el saldo pendiente siguiente. Las entregas anteriores a V50
conservan su historial comercial sin devengamiento retroactivo.

`GET /api/commissions/balances?status=ALL&page=0&size=20` requiere
`ADMIN_ALL` y devuelve un resumen acumulado de vendedores y revendedores
con movimientos. Cada fila incluye el ID, el tipo de beneficiario, el nombre
y los importes y cantidades de movimientos pagados y pendientes.
`PENDING` incluye cualquier beneficiario con movimientos pendientes,
incluidos ajustes. `PAID` incluye a quienes tienen todos sus movimientos
pagados. El filtro se aplica antes de paginar.

`GET /api/commissions/sellers/{sellerId}?dateMin=&dateMax=&page=0&size=20`
devuelve el nombre del beneficiario (`name`), el saldo pendiente completo, el
total del período y sus movimientos paginados. El período usa días inclusivos de
Buenos Aires y por defecto va del inicio del mes a hoy. Cada movimiento
identifica el pedido, su cliente (`customer`), el producto, el importe, la fecha
del movimiento y la fecha de pago si corresponde.

`POST /api/commissions/sellers/{sellerId}/settlements` registra el pago
realizado al vendedor. Requiere el `token` de `pending`, un saldo positivo
y `ADMIN_ALL`. Si cambió el saldo se devuelve `409` y se consulta de nuevo.
El pago y la vinculación de sus movimientos son atómicos y guardan importe,
fecha, administrador y auditoría.

## Revendedores (Fase 9.3)

V53 permite asignar un plan a un cliente `RESELLER` (`app.reseller_commission_plans`).
Una FK compuesta a `customers (id, kind)` acepta solo revendedores y les impide
perder la marca mientras tengan plan. Quitar la marca también exige no tener
comisiones sin liquidar, para que sigan accesibles desde la liquidación.

Al entregar una venta cargada a la cuenta de un revendedor activo con plan, sea
de un cliente asociado o una compra propia, se registra su comisión además de la
del vendedor. Usa el plan asignado en ese momento, sin efecto retroactivo, y las
mismas fórmulas, prioridades de reglas y costo congelado que la de vendedores.
Las ventas de revendedor no admiten devoluciones, así que su comisión no tiene
ajustes negativos.

Cada movimiento y cada liquidación pertenecen a un solo beneficiario: vendedor
(`seller_id`) o revendedor (`reseller_id`). Una FK compuesta impide vincular un
movimiento con la liquidación de otro beneficiario. Cada línea de venta devenga
como máximo una comisión por tipo de beneficiario y devolución
(`UNIQUE (sale_item_id, return_id, beneficiary)`).

`GET /api/commissions/resellers/{customerId}` y
`POST /api/commissions/resellers/{customerId}/settlements` funcionan igual que
los de vendedores, con `ADMIN_ALL`. La auditoría registra
`COMMISSION_SETTLEMENT` sobre el cliente.
