# Funcionalidades frontend pendientes por soporte/API

Estado actualizado: 2026-09-24. Este documento distingue funciones ya conectadas,
funciones con comandos pero sin una lectura suficiente y operaciones que no son
de usuario final. La interfaz no simula respuestas faltantes.

## Falta una proyección de lectura adecuada

- **Detalle/estado de cuenta de cliente:** `GET /api/customers` devuelve saldo
  agregado, pero no hay consulta de movimientos/deudas por `customerId`. Los
  listados de ventas/pagos permiten búsqueda por nombre/número, no filtrado
  estable por ID. Por eso no hay un estado de cuenta completo por cliente.
- **Pago a deuda específica:** el comando admite `saleId`, pero la UI solo ofrece
  FIFO. Falta consultar las deudas abiertas del cliente por `customerId` para
  permitir una selección segura.
- **Devoluciones de venta:** existe `POST /api/sales/{saleId}/returns`, pero
  requiere `saleItemId` y cantidad por línea. No existe `GET /api/sales/{saleId}`
  ni el listado de ventas incluye `orderId`/líneas; no es posible construir el
  formulario sin elegir IDs de líneas reales. El comando tampoco devuelve dinero
  ni acredita la cuenta corriente.
- **Historial de intentos de entrega:** la UI puede registrar `DELIVERED`/`FAILED`
  y cobros, pero `/api/orders/{id}` no devuelve los intentos anteriores. El estado
  final sí aparece en el detalle.
- **Edición de pedidos con descuentos/listas múltiples:** el comando existe, pero
  el detalle no expone el porcentaje de descuento general original ni un
  `priceListId` de cabecera. La UI limita la edición a pedidos confirmados sin
  descuentos guardados y con una sola lista para no borrar snapshots/reglas sin
  que el usuario lo advierta.
- **Rol actual del usuario:** `GET /api/users` no incluye rol ni autoridades
  asignadas; se puede invitar/crear seleccionando `ADMIN` o `SELLER`, pero la UI
  no inventa un rol al listar usuarios.
- **Reasignación selectiva de clientes a un vendedor:** el comando acepta
  `customerIds`, pero no existe filtro de clientes por `sellerId`. La UI sí puede
  reasignar todos los clientes de un vendedor y opcionalmente sus pedidos
  pendientes. Reasignar pedidos funciona con los IDs seleccionados de la primera
  página cargada.
- **Dashboard de vendedor:** `/api/dashboard` requiere `ADMIN_ALL`; vendedores se
  dirigen al listado de pedidos, ya que no existe proyección de dashboard con
  alcance vendedor.
- **Ajuste de inventario para `STOCK_ADJUST` sin `ADMIN_ALL`:** el comando de
  ajuste acepta `STOCK_ADJUST`, pero los GET de inventario y movimientos requieren
  `ADMIN_ALL`; un usuario solo con `STOCK_ADJUST` no puede abrir la página para
  completar el ajuste.

## No existe soporte backend suficiente

- **Lectura de auditoría:** no hay endpoint de consulta de eventos.
- **CRUD de roles/permisos y su asignación:** no hay API; los roles disponibles
  en creación/invitación son `ADMIN` y `SELLER`.
- **Dashboard global de outbox/worker:** solo se puede consultar el estado de una
  solicitud de notificación individual.
- **Corrección o reversión de pagos:** no existe comando de pago correctivo.
- **Multi-depósito:** sigue pendiente en backend.
- **Configuración de proveedor Email/WhatsApp:** URLs/tokens se cargan como
  variables del entorno; la UI puede solicitar y consultar envíos, pero no
  gestionar credenciales/configuración.

## Operación, no interfaz de usuario

- Confirmar primera ejecución del workflow de CI PostgreSQL.
- Activar backups programados y copia externa.
- Añadir verificación ArchUnit/Spring Modulith.
- Operar el proveedor webhook y sus secretos en el despliegue.

## Cobertura frontend aún incompleta

- Pruebas E2E con Playwright y auditoría visual automatizada.
- Pruebas específicas de navegación/forms mobile; los módulos nuevos sí usan
  layouts responsive.
- Paginación/filtrado URL en todos los listados: varios usan el primer bloque de
  20 elementos y no exponen controles de página activos.
