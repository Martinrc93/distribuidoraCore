# Permisos de pedidos y ventas — Fase 5.4

La autorización de una acción y el alcance de sus datos son controles separados.
`ADMIN_ALL` autoriza las acciones y mantiene acceso global. Los demás usuarios
solo operan sobre pedidos asignados a su perfil seller activo, incluidos los
pedidos sin vendedor de clientes asignados. Un permiso individual nunca elimina
ese filtro. Recursos ajenos responden `404`; la falta de permiso responde `403`.

| Permiso | Operación |
|---|---|
| `ORDER_CREATE` | Confirmar pedidos nuevos |
| `ORDER_EDIT` | Editar líneas y cantidades de pedidos confirmados |
| `ORDER_CANCEL` | Cancelar pedidos sin pagos |
| `ORDER_REACTIVATE` | Reactivar pedidos cancelados sin pagos |
| `SALE_RETURN` | Devolver cantidades de ventas entregadas |
| `ORDER_PRICE_OVERRIDE` | Establecer o cambiar precios manuales |
| `ORDER_DISCOUNT` | Establecer o cambiar descuentos de línea y pedido |
| `ORDER_PRICE_LIST_CHANGE` | Cambiar la lista elegida para el pedido |
| `ORDER_DOCUMENT_EXPORT` | Imprimir o descargar comprobantes A4 y ticket |
| `ORDER_NOTIFY` | Solicitar envío y consultar el estado de su notificación |

Los permisos de precios, descuentos y lista complementan `ORDER_CREATE` o
`ORDER_EDIT`; por sí solos no autorizan guardar pedidos. `ORDER_EDIT` permite
conservar los precios y descuentos históricos sin adquirir la capacidad de
cambiarlos. Se comparan con los snapshots persistidos bajo bloqueo del pedido;
también se reconocen listas históricas identificadas solo por su código.

La reactivación conserva deuda e historial y deja pendiente de preparación;
la cancelación conserva las restricciones de pagos y compensa solo el stock
descontado. Las devoluciones siguen sin emitir reembolsos ni créditos monetarios.
Los permisos no cambian estas reglas comerciales ni eliminan auditoría.

`ORDER_PREPARE`, `SALE_DELIVER` y `SALE_PAYMENT` mantienen su autorización y alcance.
La revisión del administrador continúa reservada a `ADMIN_ALL`; no se delega
mediante `ORDER_REVIEW` a otros roles.

## Actualización de instalaciones

V43 incorpora ocho permisos nuevos. Se asignan inicialmente a ADMIN. Los roles
que ya tenían ORDER_CREATE reciben ORDER_DOCUMENT_EXPORT y ORDER_NOTIFY para
conservar su acceso anterior a comprobantes; no reciben edición, cancelación,
reactivación, devoluciones, precios manuales ni descuentos. La migración invalida
JWT y refresh tokens de los usuarios cuyos permisos cambiaron, una sola vez.
Un rol creado después selecciona cada permiso explícitamente desde el catálogo.

El resto de las áreas mantiene sus reglas vigentes; este cambio no habilita
clientes globales, stock, compras, administración, resumen ni reportes.
