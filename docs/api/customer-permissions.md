# Permisos de Clientes y cuentas corrientes

Fase 5.4, segunda área. La autorización de cada acción es independiente del
alcance: `ADMIN_ALL` permite acceso global; otros roles siguen limitados a sus
clientes y pedidos asignados mediante un perfil de vendedor activo. Un permiso
no convierte a un rol personalizado en administrador.

## Operaciones

| Permiso (o ADMIN_ALL) | Contrato | Comportamiento |
|---|---|---|
| CUSTOMER_VIEW | GET /api/customers/{id} y /{id}/orders | Ficha e historial autorizado. |
| CUSTOMER_CREATE | POST /api/customers | Alta; sin alcance global, asignación automática al perfil propio. |
| CUSTOMER_EDIT | PUT /api/customers/{id} | Datos de contacto; conservar lista no exige permiso para cambiarla. |
| CUSTOMER_STATUS_CHANGE | PATCH /api/customers/{id}/status | Activar o desactivar un cliente asignado. |
| CUSTOMER_PRICE_LIST_CHANGE | PATCH /api/customers/{id}/price-list | Asignar o quitar la lista predeterminada, sin exigir CUSTOMER_EDIT. |
| ACCOUNT_SETTLE | POST /api/customers/{id}/account-payments/settlement | Pagos combinados parciales o liquidación FIFO de la deuda visible. |
| CUSTOMER_STATEMENT_EXPORT | GET /api/documents/customers/{id}/statement | Imprimir o descargar el estado de deuda autorizado, sin exigir CUSTOMER_VIEW. |
| REPORTS_VIEW | GET /api/reports/customers/{id}/purchases y GET /api/documents/customers/{id}/purchases | Consultar, imprimir o descargar las [compras por período](customer-purchases.md). Los roles asignados ven las ventas entregadas de sus pedidos de clientes asignados. |

La consulta básica paginada de clientes conserva acceso autenticado y alcance
asignado. `SALE_PAYMENT` sigue autorizando el pago individual/FIFO anterior y
la lectura de deudas; no concede `ACCOUNT_SETTLE`. Los detalles de pagos están
en [pagos de cuenta corriente](account-payments.md).

## Asignación y lista predeterminada

El alta y la edición aceptan `businessName`, `cuitId`, `email`, `phone`,
`address`, `zone`, `sellerId` y `priceListId`. Sin `ADMIN_ALL`, omitir `sellerId`
lo resuelve al perfil propio; enviar otro vendedor devuelve `403`. No se pueden
desasignar, tomar ni editar clientes ajenos. Un ID de cliente inexistente o ajeno
devuelve `404`. La validación de alcance de las escrituras se realiza bajo bloqueo
del cliente; los cambios y su auditoría participan de la misma transacción.

En el alta, una lista no nula exige también `CUSTOMER_PRICE_LIST_CHANGE`.
En la edición completa, cambiar o quitar la lista exige ese mismo permiso;
conservar el ID previo permite modificar contactos incluso si esa lista quedó
inactiva. Una lista nueva debe estar activa. El permiso es distinto de
`ORDER_PRICE_LIST_CHANGE`, que cambia la lista de un pedido sin modificar la
configuración persistente del cliente. No se reescriben precios históricos.

`GET /api/customers/assignment-context` admite `CUSTOMER_CREATE`, `CUSTOMER_EDIT`
o `ADMIN_ALL` y devuelve solo el perfil propio activo (`id`, `displayName`,
`email`), o `404`. Los formularios asignados no consultan el catálogo global de
vendedores. `GET /api/zones` admite esos mismos permisos para cargar las opciones;
crear, editar o eliminar zonas sigue requiriendo administrador.

## Saldos y lecturas independientes

Listado, ficha y pagos usan una única definición de saldo. El administrador ve
`customer.balance`, incluidos créditos. Otros roles ven la suma de deudas de
ventas CONFIRMED/DELIVERED que debe la cuenta del cliente
(`sale.sales.account_customer_id`, V51) y que corresponden a sus pedidos (o a
pedidos sin vendedor cuyo cliente destinatario está asignado), limitada al saldo
global positivo. Sin revendedores, la cuenta y el destinatario coinciden. Cada
deuda se limita al menor valor
entre ledger neto positivo y `total - paid` positivo. El filtro `hasBalance`
usa este saldo antes de contar y paginar. El historial aplica el alcance también
antes de sus conteos.

`GET /api/customers/{id}/account-balance` admite `ACCOUNT_SETTLE`, `SALE_PAYMENT`
o `ADMIN_ALL` y devuelve únicamente `id`, `name`, `balance` y `status`. Permite
cobrar y actualizar un saldo en conflicto sin acceso a ficha ni historial.
Una liquidación asignada puede dejar deuda global de otro vendedor: no imputa
sus ventas ni expone su saldo. Contabilidad, pagos y auditoría se actualizan
atómicamente; un exceso o cambio de saldo devuelve `409` sin cobro parcial.

## Migración y valores iniciales

V44 añade los siete permisos al catálogo y a ADMIN. No habilita estas nuevas
acciones a SELLER ni a roles personalizados por defecto. La excepción es
`CUSTOMER_STATEMENT_EXPORT`: se concede a los roles existentes con SALE_PAYMENT
para conservar su acceso anterior a documentos. Los roles nuevos seleccionan
sus permisos explícitamente. La migración revoca refresh tokens e incrementa
la versión solo de usuarios de roles que recibieron permisos; un usuario
afectado por varios permisos se invalida una vez.

La interfaz consulta el catálogo existente en Roles y permisos. Cada acción de
Clientes se muestra por su propio permiso; ficha, edición, lista, cobro y
documentos no dependen entre sí. El filtro global de vendedores y su catálogo
siguen reservados a ADMIN_ALL.

## Revendedores (Fase 9.2)

V52 agrega `kind` (`REGULAR` o `RESELLER`) y `reseller_id` a los clientes y el
permiso `RESELLER_MANAGE`, otorgado a ADMIN y reservado a ese rol. Los endpoints
exigen `ADMIN_ALL` y `RESELLER_MANAGE`; sin ellos devuelven `403`.

| Operación | Contrato | Comportamiento |
|---|---|---|
| Marcar o quitar la marca | `PATCH /api/customers/{id}/kind` con `{ "kind": "RESELLER" \| "REGULAR" }` | `204`. Repetir el valor actual no cambia nada. |
| Asociar o desasociar | `PATCH /api/customers/{id}/reseller` con `{ "resellerId": "uuid" \| null }` | Devuelve `customerId`, `resellerId` y `pendingOrdersKept`. |
| Preparar la asignación | `GET /api/customers/{id}/reseller-assignment` | `kind`, revendedor actual, `associatedCustomersCount` y hasta 20 pedidos sin entregar con su cuenta, más `pendingOrdersCount`. Desde V53 también `commissionPlan` (nombre o `null`) y `pendingCommissionCount`. |

Reglas, también impuestas por restricciones de base de datos:

- Un cliente tiene como máximo un revendedor, que debe ser un cliente `RESELLER`
  activo. Un revendedor no tiene revendedor y un cliente no es su propio revendedor.
- Un cliente asociado no puede marcarse como revendedor. Un revendedor conserva la
  marca mientras tenga clientes asociados, un plan de comisión o comisiones sin
  liquidar (V53; ver [comisiones](commission-plans.md#revendedores-fase-93)).
- Los conflictos devuelven `409` con `code: RESELLER_CONFLICT` y un `detail`
  legible; un ID inexistente devuelve `404` y un ID inválido, `400`.
- Asociar o quitar un revendedor solo afecta pedidos nuevos: los confirmados y
  la deuda existente conservan la cuenta que los debe (`sale.sales.account_customer_id`).
  La auditoría registra `CUSTOMER_RESELLER_MARK` y `CUSTOMER_RESELLER_ASSIGN`.

El listado de clientes devuelve `kind`, `resellerId` y `reseller` (nombre) y
acepta los filtros `kind` y `resellerId`, aplicados antes de contar y paginar.
La ficha agrega esos campos y `associatedCustomersCount`. Los cobros de cuentas
de revendedor quedan reservados a `ADMIN_ALL`; ver
[pagos de cuenta corriente](account-payments.md).
