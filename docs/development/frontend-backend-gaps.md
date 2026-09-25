# Funcionalidades frontend pendientes por soporte/API

Estado actualizado: 2026-09-25. Este documento distingue funciones ya conectadas,
funciones con comandos pero sin una lectura suficiente y operaciones que no son
de usuario final. La interfaz no simula respuestas faltantes.

## Nuevas capacidades conectadas al frontend

- Programación de vigencias de precios, historial paginado y cancelación de
  precios futuros por lista/producto.
- Administración `ADMIN_ALL` de reglas de descuentos por línea/pedido, incluyendo
  alcance, fechas y prioridad. El backend resuelve el descuento al confirmar.
- Administración de depósitos, balances por depósito, ajustes y transferencias
  con saldo de origen visible; los movimientos identifican el depósito.
- Selección de depósito en pedidos para usuarios que pueden consultar depósitos;
  el ID queda en el intento idempotente. Los demás pedidos usan `CENTRAL`.
- Roles de usuarios visibles como datos de solo lectura.

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
- **Edición de pedidos con descuentos/listas múltiples:** el detalle ya expone el
  porcentaje/regla de descuento general y los descuentos/reglas por línea, pero
  la UI todavía limita la edición a pedidos confirmados sin descuentos y con una
  sola lista entre líneas. Falta diseñar cómo preservar y volver a resolver reglas
  automáticas al editar; el detalle tampoco expone una lista de precios de cabecera.
- **Reasignación selectiva de clientes a un vendedor:** el comando acepta
  `customerIds`, pero no existe filtro de clientes por `sellerId`. La UI sí puede
  reasignar todos los clientes de un vendedor y opcionalmente sus pedidos
  pendientes. Reasignar pedidos funciona con los IDs seleccionados de la primera
  página cargada.
- **Dashboard de vendedor:** `/api/dashboard` requiere `ADMIN_ALL`; vendedores se
  dirigen al listado de pedidos, ya que no existe proyección de dashboard con
  alcance vendedor.
## Capacidades soportadas que siguen pendientes o limitadas en frontend

- **Reasignación de roles y edición de permisos:** el backend ofrece estos
  comandos, pero la UI solo muestra los roles actuales. La edición quedó aplazada
  por decisión del usuario.
- **Selección de depósito para usuarios no administradores:** `GET
  /api/inventory/depots` requiere `ADMIN_ALL`; los pedidos de otros usuarios usan
  el depósito predeterminado `CENTRAL` hasta que exista una lectura autorizada.
- **Ajustes para usuario solo con `STOCK_ADJUST`:** las lecturas de inventario y
  depósitos requieren `ADMIN_ALL`, por lo que esos usuarios no pueden abrir la
  pantalla frontend aunque el comando permita `STOCK_ADJUST`.

## No existe soporte backend suficiente

- **Lectura de auditoría:** no hay endpoint de consulta de eventos.
- **Dashboard global de outbox/worker:** solo se puede consultar el estado de una
  solicitud de notificación individual.
- **Corrección o reversión de pagos:** no existe comando de pago correctivo.
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
