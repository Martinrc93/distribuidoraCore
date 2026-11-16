# Preparación y revisión administrativa

V42 conserva los estados comerciales `CONFIRMED`, `DELIVERED` y `CANCELLED`.
La preparación (`PENDING` / `PREPARED`) y la revisión administrativa son independientes.

## Preparar

`POST /api/orders/{orderId}/prepare` devuelve `204`, sin cuerpo obligatorio.
Requiere `ORDER_PREPARE` o `ADMIN_ALL`. El permiso se asigna inicialmente a
`ADMIN` y `SELLER`; los vendedores solo operan sobre sus pedidos.

- Confirmar registra venta, pagos/deuda y preparación pendiente, sin mover stock.
- Preparar bloquea pedido/venta, descuenta cantidades actuales agrupadas y
  ordenadas por producto mediante `SALE`, guarda `prepared_at` y
  `prepared_by_user_id` y audita `ORDER_PREPARE`.
- Solo admite pedido y venta `CONFIRMED`. Un reintento ya preparado no genera
  movimientos ni auditoría adicionales, incluso ante concurrencia.
- Entregar desde `PENDING` prepara dentro de la misma transacción, autorizado por
  `SALE_DELIVER` o `ADMIN_ALL`. `FAILED` conserva la preparación anterior.
- Editar confirmados requiere administrador: pendientes sin stock; preparados con
  movimientos compensatorios. Productos nuevos o incrementos requieren producto activo.
- Cancelar revierte el efecto neto existente y vuelve a `PENDING`. Reactivar
  restaura deuda y deja pendiente de preparación, sin mover stock.
- Un error revierte todos los cambios de la transacción: stock, cobros, estado y auditoría.

Los históricos no cancelados quedan `PREPARED`, con `legacy_fulfillment=true`:
su stock ya se descontó al confirmar. V42 no agrega movimientos ni inventa fecha
u operador de preparación. Los cancelados quedan `PENDING`.

## Confirmar la revisión

`POST /api/orders/{orderId}/review` devuelve `204` y requiere `ADMIN_ALL`.
`ORDER_REVIEW` pertenece al catálogo y solo se puede asignar a `ADMIN`.

Un pedido con `created_by_admin=false` y `reviewed_at=null` está pendiente.
Los vendedores pueden crearlo, prepararlo, cobrar y entregarlo durante el día.
El distintivo «Vendedor · Pendiente de revisión» permanece para administradores
hasta revisar, incluso después de entregar o cancelar. No se infieren como
vendedores los creadores históricos desconocidos.

La revisión registra `reviewed_at`, `reviewed_by_user_id` y auditoría `ORDER_REVIEW`.
No cambia creador, estado comercial, preparación, stock, pagos ni deuda.
Repetirla conserva el primer revisor y no duplica auditoría.

La revisión al cierre del día es manual e individual, desde Pedidos, detalle o
Resumen. Resumen muestra hasta cinco pendientes de todas las fechas y su total,
con enlace al listado completo; así conserva pendientes de días anteriores.

## Lecturas y errores

`GET /api/orders` agrega `fulfillmentStatus` y `reviewPending` por fila y admite
`fulfillmentStatus=PENDING|PREPARED` y `reviewPending=true`. Preparación selecciona
solo confirmados («A preparar» / «Listos para entregar»). Los filtros se combinan
con fechas, creador, cobro, búsqueda y alcance del vendedor antes de paginar/contar.

El detalle agrega `fulfillmentStatus`, `preparedAt`, `preparedByUserId`,
`legacyFulfillment`, `createdByAdmin`, `createdByUserId`, `reviewPending`,
`reviewedAt` y `reviewedByUserId`.

Errores: `400` filtro de preparación inválido; `401` sin sesión; `403` sin permiso;
`404` pedido inexistente o fuera del alcance del vendedor; `409` estado incompatible,
producto inactivo al preparar o pedido que no fue creado por vendedor al revisarlo.
