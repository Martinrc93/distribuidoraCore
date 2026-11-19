# Fase 9 — Revendedores: titular de la deuda separado del destinatario — Diseño

Fecha: 2026-11-18. Rama: `support` (HEAD `7a413eb`).

Estado: **reglas de negocio respondidas el 2026-11-18 y supuestos S1–S6
confirmados. Etapas 9.1 (V51) y 9.2 (V52) implementadas; 9.3 pendiente de S7.**
ADR asociado: [ADR-010](../../adr/ADR-010-reseller-debt-holder.md) (aceptado).

## Objetivo

Que los pedidos de los clientes asociados a un revendedor se debiten en la
cuenta corriente del revendedor sin dejar de registrar al cliente final como
destinatario del pedido: entrega, documentos, numeración, historial y reportes
de compras.

## Regla confirmada

[Plan](../plans/2026-11-12-requerimientos-isma-leo.md), «Decisiones», punto 3:
la deuda es **siempre** del revendedor. El revendedor tiene clientes asociados y
su propia cuenta corriente; cómo les cobra a ellos queda fuera del sistema. Esta
regla prevalece sobre el «si asume la deuda» de la propuesta inicial de la fase.

Consecuencia: la cobranza del revendedor a sus clientes no se registra. Para la
distribuidora, cada pedido de un cliente asociado es una venta al revendedor
entregada en otro destino.

## Diagnóstico del modelo actual

Rutas relativas a `backend/src/main/java/com/distribuidora/`, salvo indicación.

### Un mismo `customer_id` cumple tres papeles

| Papel | Persistencia | Escritura |
|---|---|---|
| Destinatario | `orders.orders.customer_id`, `sale.sales.customer_id` (V3) | `order/application/OrderConfirmationService.java:201-210` |
| Titular de la deuda | `customer.account_ledger.customer_id` (V6), `customer.customers.balance` | `OrderConfirmationService.java:214-220` |
| Pagador | `payment.payments.customer_id` (V3) | `OrderConfirmationService.java:213`, `customer/application/AccountPaymentService.java:141-146` |

Todas las escrituras toman el cliente de la venta como cuenta:

- Confirmación: débito y saldo del cliente del pedido. El límite de crédito se
  proyecta sobre su saldo (`OrderConfirmationService.java:182-191`).
- Edición: ajuste del ledger al mismo cliente (`:318-333`).
- Entrega con cobro: pagos, créditos y saldo de `sale.customer_id`
  (`order/application/DeliveryLifecycleService.java:118-139` y `:144-196`).
- Cancelación y reactivación: crédito y débito al mismo cliente (`:219-225` y `:255-261`).
- Pagos de cuenta: FIFO sobre `s.customer_id` (`AccountPaymentService.java:172-197`).

Ningún dato permite hoy que la venta de un cliente debite otra cuenta.

### Dónde se aplica FIFO

Siempre por `(s.created_at, s.id)`. La deuda de cada venta es el menor valor
entre el neto del ledger y `total - paid`.

1. `AccountPaymentService.lockCandidateSales` (`:172-197`), usado por el pago
   individual y por la liquidación combinada (`:59-95`).
2. Pago de deuda anterior al confirmar: `OrderConfirmationService.payPreviousDebts`
   (`:675-685`) delega en el punto 1 antes de crear la venta nueva.
3. Cobro al entregar con deuda anterior: primero la venta entregada y luego las
   anteriores del mismo cliente (`customer/application/DeliveryDebtQuery.java:23-40`,
   ancla `anchor.customer_id = s.customer_id`; `DeliveryLifecycleService.java:144-196`).

La imputación a una venta concreta ya existe (`saleId` en
`POST /api/customers/{id}/account-payments`) y la usa la pantalla Pagos
(`frontend/src/features/payments/PaymentsPage.tsx:115`). «Registrar pago» en
Clientes imputa solo por FIFO.

### Controles de alcance

- `shared/security/CurrentUserAccess.java:54-75`: sin `ADMIN_ALL`, un usuario
  accede a clientes con `customers.seller_id` igual a su perfil y a pedidos con
  `orders.seller_id` igual a su perfil o, si es nulo, del cliente asignado.
- El predicado `(o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))`
  se repite en `customer/application/CustomerAccountBalanceQuery.java:23-37`,
  `AccountPaymentService.java:188-193`, `DeliveryDebtQuery.java:33-38`,
  `document/application/AccountStatementDocumentService.java:42-48` y
  `dashboard/application/ReadQueryService.java` (deudas, pedidos, ventas y pagos).
  En todos, `c` es el cliente de la venta: al separar la cuenta, el respaldo
  debe apuntar explícitamente al destinatario.
- Todo rol distinto de `ADMIN` opera con un perfil de vendedor
  ([administración de usuarios](../../api/identity-admin.md)). No existe otro
  tipo de alcance.

### Lecturas que asumen titular = destinatario

- La antigüedad de deuda filtra el ledger con `customer_id = s.customer_id`
  (`dashboard/application/DashboardAnalyticsService.java:98`).
- El detalle del pedido lee la cuenta de la venta con el cliente del pedido
  (`ReadQueryService.java:488`) y muestra `customerBalance` del destinatario
  (`:422` y `:459`).
- Deudas del cliente (`ReadQueryService.java:376-405`), estado de deuda
  (`AccountStatementDocumentService.java:61-64`) y saldo con alcance filtran
  por `s.customer_id`.
- Deuda total, deudores y mayores saldos del Dashboard usan `customers.balance`
  (`DashboardAnalyticsService.java:66-67` y `:88-91`). Siguen siendo correctos
  si el saldo vive en la cuenta del titular.

### Otros hallazgos

- Las devoluciones no tienen efecto financiero para ningún cliente:
  `sale/application/SaleReturnService.java:113-121` registra stock y comisión,
  sin ledger ni pagos. [Devoluciones](../../api/sale-returns.md) lo declara pendiente.
- [Límites de módulos](../../architecture/module-boundaries.md) asigna el ledger
  a `payment`, pero ese paquete solo tiene `package-info.java`. El ledger se
  escribe por JDBC en siete sentencias de tres servicios de `order` y `customer`,
  más la demo; cada una decide la cuenta por separado.
- La lista predeterminada sale del cliente del pedido o de `GENERAL`
  (`pricing/application/PricingQueryService.java:190-204`).
- La comisión se atribuye a `coalesce(o.seller_id, c.seller_id)`
  (`sale/application/CommissionAccrualService.java:39`). Planes, movimientos y
  liquidaciones solo admiten perfiles de vendedor, y cada línea de venta acepta
  un único movimiento (V49 y V50).
- La reasignación de clientes mueve `customers.seller_id` y el vendedor de los
  pedidos `CONFIRMED` (`seller/application/SellerCommandService.java:226-263`).
  No toca deuda.
- La numeración de pedidos es por cliente (V36): debe seguir en el destinatario.
- El límite de crédito es global y se proyecta sobre el saldo del cliente del
  pedido ([límite de crédito](../../api/credit-limit.md)).

## Decisiones (respuestas del 2026-11-18)

| # | Pregunta | Respuesta | Regla de diseño |
|---|---|---|---|
| P1 | Clientes asignados | Cada cliente tiene un revendedor; lo asigna el administrador. | Asociación exclusiva (`customers.reseller_id`). Marcar revendedores y asociar, reasignar o quitar clientes requiere `RESELLER_MANAGE`, reservado a ADMIN. |
| P2 | Permisos del revendedor | No necesita acceso: el sistema lleva sus cuentas y sus pedidos. Lo maneja el administrador. | El revendedor no tiene usuario. Su operación queda a cargo de la administración (S1). |
| P3 | Lista y documentos | La lista predeterminada; después se puede elegir una para cada cliente. | Precios sin cambios: lista asignada al cliente final o `GENERAL`, y cambio por pedido con `ORDER_PRICE_LIST_CHANGE`. Documentos (S2). |
| P4 | Cobros | Los registra el administrador. El pedido queda en el cliente, la deuda pasa al revendedor y cada importe tiene que estar vinculado a su pedido. | Pagos en cuentas de revendedor: solo `ADMIN_ALL`. Cada débito, pago y asiento referencia su venta y su pedido, como hoy. Dinero del cliente final (S3). |
| P5 | Imputación | No se había planteado. | Se mantiene el comportamiento actual (S4). |
| P6 | Devoluciones | Un pedido entregado no se puede devolver. | Las ventas cargadas a un revendedor no admiten devoluciones. Antes de entregar se corrigen editando o cancelando (S5). |
| P7 | Reasignación | Lo generado bajo un revendedor queda ahí. Si el cliente cambia de revendedor, hay que cancelar los pedidos. | El titular de cada venta no cambia. La reasignación afecta solo pedidos nuevos; mover un pedido exige cancelarlo y volver a cargarlo (S6). |
| P8 | Comisiones | Sí, si se configura antes. | Un plan puede asignarse a un revendedor. Devenga al entregar solo si estaba asignado en ese momento, sin efecto retroactivo (S7). |
| P9 | Arranque | No hay datos para cargar. | Sin carga inicial ni migración de saldos. |

### Supuestos

Completan respuestas parciales. S1–S6 se confirmaron el 2026-11-18 y están
implementados en 9.2; S7 sigue pendiente para 9.3.

- **S1. Operación administrativa (P2).** Marcar, asociar y cobrar en cuentas de
  revendedor exigen `ADMIN_ALL`, incluidos los pagos al confirmar y al entregar.
  Pedidos y entregas de sus clientes siguen los permisos y el alcance actuales:
  como la administración asigna los clientes, decide quién más los opera.
- **S2. Documentos (P3).** Remito y ticket muestran los precios del pedido, como
  hoy. Los pedidos con deuda del revendedor no ofrecen «saldo anterior» en el
  remito, porque el cliente final no le debe a la distribuidora.
- **S3. Dinero del cliente final (P4).** Si la distribuidora lo recibe, el
  administrador lo registra como pago a la cuenta del revendedor, imputado a ese
  pedido.
- **S4. Imputación (P5).** Sin pedido elegido, FIFO desde la venta más antigua de
  la cuenta del revendedor, aunque sea de otro cliente. En Pagos se puede elegir
  un pedido, como hoy. Cada asignación queda vinculada a su pedido.
- **S5. Devoluciones (P6).** La regla vale para toda venta cargada a la cuenta de
  un revendedor, incluidas sus compras propias. Los demás clientes conservan las
  devoluciones actuales.
- **S6. Reasignación (P7).** Se permite con pedidos pendientes. Antes de guardar,
  el diálogo los lista para que el administrador decida si los cancela y vuelve
  a cargar; no se cancelan automáticamente.
- **S7. Comisiones (P8).** Devengan sobre las ventas cargadas a la cuenta del
  revendedor (pedidos de sus clientes y compras propias), con las fórmulas de la
  Fase 8, y se liquidan como las de un vendedor: el administrador registra el
  pago. Si el pedido tiene además un vendedor con plan, cada uno devenga la suya.

## Propuesta

### Conceptos

- **Destinatario**: cliente que recibe el pedido. Sigue en `orders.customer_id`
  y `sales.customer_id`. Define entrega, documentos, numeración, lista de
  precios, historial y reportes de compras.
- **Titular**: cuenta corriente que debe la venta. Nuevo
  `sales.account_customer_id`. Define débitos, pagos, FIFO, saldo, límite de
  crédito y estado de cuenta.
- **Revendedor**: cliente con `kind = 'RESELLER'`, sin usuario. Tiene su propia
  cuenta, puede comprar para sí y es titular de los pedidos de sus clientes
  asociados (`customers.reseller_id`).

Sin revendedores, titular = destinatario y el comportamiento es el actual.

### Modelo de datos (borrador)

Etapa 9.1, implementada en `V51__add_sale_account_holder.sql`, sin cambio
funcional:

- Verificación previa: la migración falla con un mensaje claro si algún asiento o
  pago no coincide con el cliente de su venta.
- `sale.sales.account_customer_id` obligatorio, completado con `customer_id`, con
  FK a `customer.customers` y unicidad `(id, account_customer_id)`.
- FK compuestas `(sale_id, customer_id) → sale.sales (id, account_customer_id)`
  en `customer.account_ledger` y `payment.payments`.
- Índice parcial `(account_customer_id, created_at, id)` de ventas abiertas para
  FIFO, saldos y estados de cuenta.

Etapa 9.2, implementada en `V52__add_resellers.sql`:

- `customers.kind` (`REGULAR` o `RESELLER`), `reseller_id` y `reseller_kind`.
- FK compuesta `(reseller_id, reseller_kind) → customers (id, kind)`: solo se
  referencia a revendedores y la marca no puede quitarse mientras tengan clientes.
- `ck_customer_reseller_kind` exige `reseller_kind = 'RESELLER'` exactamente cuando
  hay `reseller_id`. Se escribe sin resultados NULL: con la forma
  `(... AND ...) OR (... AND kind = 'RESELLER')`, un `reseller_kind` nulo daba
  NULL, el CHECK lo aceptaba y la FK compuesta no se verificaba. La prueba de
  migración detectó ese hueco antes de publicar.
- Un cliente no es su propio revendedor y un revendedor no tiene revendedor.
- Permiso `RESELLER_MANAGE`, otorgado a ADMIN y reservado a ese rol.

Etapa 9.3 (`V53`), comisiones de revendedores:

- `app.reseller_commission_plans (customer_id, plan_id)`, con FK compuesta a
  `customers (id, kind)` para aceptar solo revendedores e impedir quitarles la
  marca mientras tengan plan.
- `sale.commission_entries` y `sale.commission_settlements` admiten como
  beneficiario un vendedor o un revendedor (exactamente uno), con la misma
  garantía de pertenencia entre movimiento y liquidación. La unicidad pasa de
  `(sale_item_id, return_id)` a `(sale_item_id, return_id, tipo de beneficiario)`.

### Invariantes

1. Toda venta tiene un titular, fijado al confirmar. Las FK compuestas impiden
   cambiarlo mientras tenga asientos o pagos.
2. Todo asiento y todo pago de una venta pertenecen a su titular y quedan
   vinculados a esa venta y a su pedido.
3. `customers.balance` sigue siendo la suma neta del ledger de esa cuenta.
4. Un revendedor no tiene revendedor, un cliente no es su propio revendedor y la
   asociación solo apunta a clientes `RESELLER`. La FK impide quitar la marca a
   un revendedor con clientes asociados.
5. Ninguna escritura recibe el titular desde el request: se lee de la venta.

### Reglas

Confirmación:

- Titular = `reseller_id` del destinatario si existe; si no, el destinatario. Se
  resuelve bajo el bloqueo de la fila del destinatario, el mismo que toma la
  numeración. Ambos deben estar activos.
- Débito, saldo y límite de crédito se calculan sobre el titular.
- Lista de precios y vendedor del pedido: sin cambios (P3).
- Con titular revendedor, «Pago de este pedido» y «Pago de deuda anterior»
  requieren `ADMIN_ALL` (S1). La deuda anterior se imputa por FIFO de la cuenta
  del titular.
- Saldo anterior del remito: no disponible si titular ≠ destinatario (S2).
- La huella de idempotencia no cambia: un reintento devuelve el pedido existente
  aunque haya cambiado la asociación.

Edición, entrega, cancelación y reactivación:

- Operan sobre el titular guardado en la venta. Orden de bloqueo: ventas por
  `(created_at, id)` y después la fila de la cuenta titular, como hoy.
- Cobro al entregar: primero la venta entregada y el resto por FIFO de la cuenta
  hasta esa venta (`DeliveryDebtQuery` anclado en `account_customer_id`). En
  ventas de revendedor solo lo registra `ADMIN_ALL` (S1 y S3).

Cobros de cuenta:

- La cuenta es el titular. FIFO sobre todas sus ventas abiertas (S4); la
  imputación a una venta concreta no cambia. Cada deuda informa pedido y
  destinatario.
- Cuentas de revendedor: solo `ADMIN_ALL` (P4).
- El saldo con alcance de un vendedor suma las ventas de la cuenta atribuidas a
  su perfil o, sin vendedor, las de destinatarios asignados a él.

Devoluciones: `409` si la cuenta titular de la venta es un revendedor (P6 y S5).

Reasignación:

- Cambiar `reseller_id` no modifica ventas existentes (P7). El diálogo informa
  los pedidos sin entregar que siguen en la cuenta anterior (S6).
- Un revendedor inactivo impide confirmar pedidos de sus clientes hasta
  reactivarlo o reasignarlos.

Comisiones (etapa 9.3):

- Al entregar, además del devengo actual del vendedor, se registra el del
  revendedor titular si tiene plan asignado en ese momento (P8 y S7).
- Mismas fórmulas, prioridades de reglas y congelamiento de costo que la Fase 8.
- Las ventas de revendedor no admiten devoluciones, por lo que su comisión no
  tiene ajustes negativos.
- Liquidación por revendedor con el mismo control de saldo y token que la de
  vendedores.

### Cambios por módulo

| Módulo | Cambio |
|---|---|
| `payment` | Servicio único de cuenta corriente: débito, crédito, FIFO, saldo visible y bloqueos, con el titular leído de la venta. Reemplaza las sentencias directas de `order` y `customer`. Nuevas aristas `order → payment`, `customer → payment` y `sale → payment`, sin ciclos (regla de slices de `ModuleBoundaryTest`). |
| `order` | La confirmación resuelve el titular. Edición, entrega, cancelación y reactivación delegan en `payment`. `DeliveryDebtQuery` por titular. Cobros de revendedor solo administrador. |
| `customer` | `kind` y `reseller_id`; comandos de marca y asociación con `RESELLER_MANAGE`, bloqueo y auditoría (`CUSTOMER_RESELLER_MARK`, `CUSTOMER_RESELLER_ASSIGN`). Saldo con alcance por titular. |
| `sale` | Bloqueo de devoluciones en cuentas de revendedor. Devengo y liquidación de comisiones de revendedores (9.3). |
| `settings` | Asignación de planes a revendedores (9.3). |
| `dashboard`, `document` | Deudas, estado de cuenta, detalle y antigüedad por titular, con el destinatario por línea. |
| `identity` | Permiso `RESELLER_MANAGE`, reservado a ADMIN. |

`pricing` no cambia.

### API

Contrato vigente de 9.2 en [Clientes y cuentas](../../api/customer-permissions.md#revendedores-fase-92),
[pagos](../../api/account-payments.md), [confirmación](../../api/order-confirmation.md)
y [proyecciones](../../api/read-projections.md). Resumen:

- `PATCH /api/customers/{id}/kind` con `{ "kind": "RESELLER" | "REGULAR" }`.
  `409 RESELLER_CONFLICT` si el cliente tiene revendedor o, al quitar la marca,
  clientes asociados (en 9.3, también plan de comisión).
- `PATCH /api/customers/{id}/reseller` con `{ "resellerId": "uuid" | null }`.
  `409` si el destino no es un revendedor activo o el cliente es revendedor; `400`
  si es el mismo cliente. Devuelve `pendingOrdersKept`.
- `GET /api/customers/{id}/reseller-assignment`: tipo, revendedor actual, clientes
  asociados y pedidos sin entregar que conservan su cuenta.
- Lecturas de clientes: `kind`, `resellerId` y `reseller`; filtros `kind` y
  `resellerId` aplicados antes de contar y paginar.
- Deudas, estado de cuenta y ventas: destinatario por línea; `GET /api/sales`
  acepta `accountCustomerId` para «Ver deuda».
- Detalle del pedido: `accountCustomerId`, `accountCustomer` y `accountKind`;
  `customerBalance` es el saldo de esa cuenta.
- Pagos y cobros sobre cuentas de revendedor sin `ADMIN_ALL`: `403`.
- `POST /api/sales/{saleId}/returns` sobre una venta de revendedor: `409`.
- Comisiones (9.3): asignación `GET`/`PUT /api/settings/commission-plans/reseller-assignments/{customerId}`
  con `expectedPlanId`; saldo y movimientos en `GET /api/commissions/resellers/{customerId}`;
  liquidación en `POST /api/commissions/resellers/{customerId}/settlements`.

### Interfaz

Aplica la [guía de UI](../../development/ui-style-guide.md): ficha del cliente,
`FormModal`, `DataTable` y el patrón obligatorio de `CustomerSelect`. Los
revendedores son clientes, así que su selector es `CustomerSelect`. Lo
implementado se documenta en la sección «Revendedores» de la guía.

- Clientes: `Badge` «Revendedor» junto al nombre y, en clientes asociados, texto
  secundario «Cuenta: {revendedor}». Filtro «Buscar por revendedor» con
  `CustomerSelect` en modo `filter`, con opciones de revendedores cargadas con
  `apiGetAllPages`.
- Acción de administración: una sola acción «Revendedor» por fila abre un
  `FormModal` compacto con el tipo de cliente (radios) y, para clientes,
  `CustomerSelect` en modo `selection` con `emptyLabel` «Sin revendedor (cuenta
  propia)». Informa que la deuda existente no cambia de cuenta y lista los
  pedidos pendientes que siguen en la cuenta anterior. Se descartaron dos
  acciones con `ConfirmationDialog`: ensanchaban 306 px una tabla que ya
  desplazaba 235 px a 1280 px; la acción única agrega 96 px.
- Ficha del revendedor: `StatCard` «Saldo de cuenta corriente», panel «Clientes
  asociados» (`DataTable` con paginación propia) y acción «Ver deuda de la cuenta»,
  que abre Ventas filtradas por cuenta con el cliente de cada venta.
- Ficha del cliente asociado: campo «Revendedor» en `dl.customer-detail-fields`
  y aviso de que sus pedidos nuevos se cargan a esa cuenta. Su saldo es solo la
  deuda propia.
- Nuevo pedido: texto de ayuda «Cuenta: {revendedor}» bajo la fila de cliente,
  vendedor y lista, sin alterar su grilla. El recuadro
  «Cuenta corriente» muestra el saldo de esa cuenta y no ofrece «Importe para el
  remito». Los pagos aparecen solo para administradores.
- Detalle del pedido y entrega: «Titular de la deuda» y saldo de la cuenta. Sin
  `ADMIN_ALL`, la entrega de un pedido de revendedor no muestra pagos y explica
  que los registra la administración.
- Ventas de revendedor: sin acción «Devolver», con un texto que explica por qué.
- Pagos: cada deuda muestra pedido y cliente final.
- Dashboard: «Ver deuda» filtra por `accountCustomerId`.
- Comisiones (9.3): asignación y liquidación de revendedores con `CustomerSelect`,
  junto a las de vendedores.

Verificación en 320, 640, 760, 1050 y 1280 px, con teclado, foco, Escape con
descarte, estados de carga y error, y ambos temas.

### Documentos

- Remito A4 y ticket 80 mm: sin cambios, salvo el saldo anterior (S2).
- Estado de deuda: por titular, con pedido y cliente final por línea.

### Migración y datos

- Antes de publicar V51, verificar en A y B con una consulta de solo lectura que
  cada asiento y cada pago coinciden con el cliente de su venta. No se pudo
  verificar desde este entorno.
- V52 no modifica clientes existentes: todos quedan `REGULAR` y sin revendedor.
- Sin carga inicial (P9).

### Etapas

| Etapa | Contenido | Tamaño | Depende de |
|---|---|---|---|
| 9.1 | **Implementada.** Titular en la venta, FK compuestas, cuenta corriente en `payment` y lecturas por titular. Sin cambio visible. | L | — |
| 9.2 | **Implementada.** Revendedores: marca y asociación, confirmación con titular, cobros solo de administración, sin devoluciones, reasignación, interfaz, estado de cuenta y Dashboard. | L | 9.1, S1–S6 |
| 9.3 | Comisiones de revendedores: asignación de plan, devengo al entregar y liquidación. | M | 9.2, S7 |

### Implementación de 9.1 (2026-11-18)

- `V51__add_sale_account_holder.sql` y `SaleAccountHolderMigrationPostgresTest`:
  backfill, columna obligatoria, FK compuestas, cuenta fija con historial y
  rechazo de datos inconsistentes.
- `payment.application`: `AccountLedger` (asientos, saldos, pagos y bloqueo de
  la cuenta) y las clases movidas desde `customer`: `AccountPaymentService`,
  `CustomerAccountBalanceQuery` y `DeliveryDebtQuery`.
- Confirmación, edición, entrega, cancelación y reactivación leen la cuenta de la
  venta y escriben con `AccountLedger`. La confirmación guarda como cuenta al
  cliente del pedido.
- Lecturas por cuenta: FIFO de cobros, saldo con alcance, deuda anterior de
  entregas, deudas del cliente, estado de deuda, antigüedad del Dashboard y saldo
  del detalle del pedido. El respaldo de alcance usa el destinatario.
- Verificación: 568 pruebas de backend sin fallos ni omisiones, con PostgreSQL
  16.4 desechable y Flyway hasta V51. Se excluyeron las mismas nueve clases que
  en fases previas (Docker y bases históricas).

### Implementación de 9.2 (2026-11-19)

- `V52__add_resellers.sql` y `ResellerMigrationPostgresTest`: tipo de cliente,
  asociación exclusiva sin cadenas, FK compuesta y permiso reservado.
- `customer`: `changeKind` y `assignReseller` con bloqueo, auditoría y
  `ResellerConflictException` (`409 RESELLER_CONFLICT` con detalle legible).
- Confirmación: resuelve la cuenta del revendedor y la vuelve a verificar después
  de numerar; rechaza saldo anterior en el remito y cobros sin `ADMIN_ALL`.
  `AccountLedger.requireCollectionAllowed` aplica la misma regla en pagos y
  entregas; las devoluciones de ventas de revendedor responden `409`.
- Lecturas: clientes con tipo y revendedor, deudas y estado de cuenta con el
  cliente final, Ventas por cuenta y detalle del pedido con la cuenta.
- Interfaz: acción y diálogo «Revendedor», filtro por revendedor, ficha con
  clientes asociados, pedido, entrega, ventas, pagos y Dashboard según la guía.
- Verificación: 576 pruebas de backend con PostgreSQL 16.4 (dos de integración
  de revendedores, migraciones V51 y V52); 385 pruebas de frontend, `tsc -b` y
  build; E2E de revendedores (320, 640, 760, 1050 y 1280 px, ambos temas, Escape)
  y de Dashboard con Brave.

### Verificación

Backend:

- titular en cada escritura: confirmación, edición, entrega, cancelación,
  reactivación, pagos y deuda anterior;
- FIFO entre clientes de un mismo revendedor e imputación a una venta concreta;
- `403` en cobros de revendedor sin `ADMIN_ALL` y `409` en sus devoluciones;
- saldo con alcance con pedidos de varios vendedores y límite de crédito;
- reintento idempotente después de cambiar la asociación;
- validaciones de marca y asociación (`400`, `403`, `404`, `409`);
- comisión de revendedor junto a la del vendedor, sin duplicados (9.3).

PostgreSQL (opt-in): backfill y FK de V51 con datos previos; rechazo de
asientos o pagos de otra cuenta; concurrencia entre dos confirmaciones de
clientes del mismo revendedor y un pago de su cuenta, sin bloqueos mutuos ni
doble cobro.

Frontend: pruebas de pantallas y diálogos. E2E del revendedor (marca, asociación,
pedido, pago FIFO entre dos clientes y estado de cuenta) en escritorio y móvil.

Comandos: `mvn clean test`, casos PostgreSQL con `POSTGRES_TEST_URL`,
`npm test`, `npm run build` y Playwright.

### Riesgos

- Una consulta de deuda que siga usando `s.customer_id` daría saldos incorrectos.
  Mitigación: FK compuestas, servicio único y una prueba por consulta revisada.
- Todas las confirmaciones de clientes de un revendedor bloquean su cuenta.
  Aceptable a la escala actual.
- V51 falla si A o B tienen asientos o pagos inconsistentes.
- La etapa 9.3 modifica tablas de comisiones de la Fase 8.

### Fuera de alcance

Acceso del revendedor al sistema, cobranza del revendedor a sus clientes,
traspasos de deuda, saldo a favor, devoluciones de ventas de revendedores,
documentos sin precios, jerarquías de revendedores y carga inicial.

### Documentación a actualizar al implementar

ADR-007 (la cuenta pasa a ser la del titular), ADR-010 (aceptado),
`account-payments.md`, `customer-permissions.md`, `credit-limit.md`,
`delivery-collection.md`, `read-projections.md`, `dashboard-analytics.md`,
`sale-returns.md`, `commission-plans.md`, `identity-admin.md`,
`module-boundaries.md`, `functionalities.md` y la guía de UI.
