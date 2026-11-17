# Precios y vigencias

Todos los endpoints están bajo `/api/pricing`. Las consultas requieren un
usuario autenticado. Las modificaciones requieren la autoridad `ADMIN_ALL` y
se registran en auditoría.

## Regla de vigencia

`catalog.product_price_history` es la fuente de verdad. Para la fecha comercial
actual se usa la última versión cuyo `effectiveOn` no supera la fecha de
`America/Argentina/Buenos_Aires`. Un precio futuro entra en vigor por fecha, sin
un job que tenga que activarlo. Los pedidos y ventas confirmados conservan sus
snapshots y no se recalculan retroactivamente.

La migración V21 establece una versión inicial con fecha de Buenos Aires a
partir del precio que existía al desplegarla. No reconstruye cambios anteriores
a V21. La tabla `catalog.product_prices` se conserva para compatibilidad con
escrituras inmediatas antiguas; las lecturas de negocio usan el historial.

## Consultar listas y precios actuales

- `GET /api/pricing/lists?page=0&size=20`
- `GET /api/pricing/lists/{listId}/prices?page=0&size=20`: filtros opcionales
  `categoryId` (rubro), `brandId`, `search` (nombre, sin distinguir mayúsculas) y
  `followStatus`, combinados antes de paginar. Cada fila incluye `categoryId` y `brandId`;
  para administradores también `cost` (los vendedores no reciben el costo).

La lista de precios devuelve, por producto, el precio efectivo hoy y el campo
`effectiveOn` que lo estableció.

## Cambiar o programar un precio

`PUT /api/pricing/lists/{listId}/products/{productId}` devuelve `204`.

```json
{
  "price": 1250.5000,
  "effectiveOn": "2026-10-01"
}
```

`effectiveOn` es opcional. Si se omite, el cambio rige hoy. Si es una fecha
futura, el precio se programa y no afecta la resolución actual. Editar la misma
lista, producto y fecha futura reemplaza el valor pendiente. Las fechas pasadas
se rechazan. El precio no puede ser negativo, superar `NUMERIC(19,4)` ni quedar
por debajo del costo vigente del producto.

## Consultar historial

`GET /api/pricing/lists/{listId}/products/{productId}/history?page=0&size=20`

Devuelve las versiones con `effectiveOn`, `recordedAt`, `updatedAt` y `scheduled`.
La paginación limita el tamaño a 100 filas.

## Cancelar un precio programado

`DELETE /api/pricing/lists/{listId}/products/{productId}/history/{effectiveOn}`
devuelve `204`. Solo elimina una entrada cuya fecha siga siendo futura. Una
fecha actual/pasada o una entrada inexistente produce un error; la cancelación
queda auditada como `PRODUCT_PRICE_SCHEDULE_CANCEL`.

## Resolver por cliente o fecha

- `GET /api/pricing/resolve?customerId={id}&productId={id}`: lista del cliente
  o `GENERAL`.
- `GET /api/pricing/resolve?customerId={id}&productId={id}&priceListId={id}`:
  lista explícita.
- Se puede agregar `asOf=2026-10-01` para consultar una fecha histórica o
  futura sin cambiar datos.

La respuesta incluye `priceListId`, `priceListCode`, `productId`, `unitPrice` y
`effectiveOn`. Si la lista seleccionada no tiene versión aplicable, se busca
una lista activa anterior según el orden de identificadores ya definido; si no
se encuentra ningún precio, se responde `404`.

## Listas que siguen a la General

`catalog.price_list_follows` (V38) marca, por lista y producto, que el precio
sigue a la lista predeterminada (`is_default`). Se implementa al escribir: cada
versión de precio de la General (actual o programada) se escribe en la misma
transacción en las listas que la siguen, así la resolución, los pedidos y los
snapshots siguen leyendo `product_price_history` sin cambios.

- **Copia:** `POST /api/pricing/lists` y `PATCH .../status` con `ACTIVE` copian a
  la lista el precio vigente y las vigencias futuras de la General para cada
  producto activo sin ningún precio en ella. Quedan con `followStatus: PENDING`.
  Los productos se bloquean antes de copiar para no perder cambios concurrentes.
  Crear o reactivar un producto también completa las listas activas sin precio.
- **Seguimiento:** `PUT .../lists/{generalId}/products/{productId}` y la
  cancelación de una vigencia de la General se aplican también a las listas que
  la siguen. Un `PUT`, una programación o una cancelación en otra lista convierte
  el precio en propio (borra el seguimiento).
- **Productos:** al subir el costo (`PUT /api/products/{id}`) las listas que siguen
  a la General no se informan como afectadas; un precio enviado para la General se
  propaga a ellas.

Endpoints (todos con `ADMIN_ALL`):

- `GET /api/pricing/lists/creation-preview` → `{ productsToCopy, defaultListId, defaultListName }`.
- `GET /api/pricing/lists/{listId}/prices?followStatus=PENDING|CONFIRMED|FOLLOWING|OWN`:
  filtro opcional; cada fila incluye `followStatus` (`PENDING`, `CONFIRMED` o `null`
  para precio propio). `GET /api/pricing/lists` incluye `pendingReviewCount`.
- `POST /api/pricing/lists/{listId}/reviews/confirm` con `{ "productIds": [...] }`
  (hasta 1000) o sin cuerpo/lista vacía para confirmar todos los pendientes de la
  lista. Responde `{ "confirmed": n }` y audita `PRICELIST_REVIEW_CONFIRM`.
- `GET /api/pricing/lists/products/{productId}/followers` → listas que siguen la
  General para el producto y todavía no tienen el aviso confirmado
  (`[{ priceListId, code, name }]`).
- `PUT /api/pricing/lists/{listId}/products/{productId}` y `PUT /api/products/{id}`
  aceptan `acknowledgeFollowers: true`: registra que el usuario aceptó el aviso y no
  se vuelve a avisar para ese producto en esas listas (siguen acompañando a la General).

La auditoría agrega `copiedFromDefault`, `propagatedToFollowers`,
`acknowledgedFollowers` o `stoppedFollowingDefault` cuando corresponde.

## Errores

- `400 INVALID_REQUEST`: precio inválido, fecha pasada o precio inferior al
  costo.
- `401 UNAUTHORIZED`: sesión ausente o inválida.
- `403 FORBIDDEN`: la mutación no tiene `ADMIN_ALL`.
- `404 NOT_FOUND`: lista, cliente, producto o precio aplicable inexistente.
- `409 CONFLICT`: regla de listas o vigencia comercial incompatible.

Al aumentar el costo del producto, el backend bloquea el producto y rechaza el
cambio si alguna vigencia futura activa queda por debajo del nuevo costo; se
debe ajustar o cancelar primero esa vigencia.

## Descuentos manuales

Solo se aplican los porcentajes explícitos de línea y pedido, con autorización
`ORDER_DISCOUNT` o `ADMIN_ALL`. Un porcentaje cero conserva el precio completo; no existen reglas
comerciales automáticas ni endpoints para administrarlas.

La migración V32 elimina las reglas guardadas y sus referencias en pedidos y
ventas. Conserva los porcentajes e importes históricos, los pagos y los saldos;
no recalcula operaciones ya confirmadas. V22 permanece en el historial de
migraciones para permitir actualizar instalaciones existentes.

## Consulta de precios para editar pedidos

`GET /api/pricing/resolve-batch` admite `orderId` opcional. Sin él requiere
ORDER_CREATE y acceso al cliente; con él requiere ORDER_EDIT y verifica tanto
el acceso al pedido como que customerId coincida con su cliente. Esto permite
editar pedidos asignados después de reasignar su cliente, sin consultar precios
usando un cliente ajeno como contexto. La caché de edición incluye el ID del pedido.
