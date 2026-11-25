# Funcionalidades frontend pendientes por soporte/API

Estado actualizado: 2026-11-25. Este documento distingue funciones ya conectadas,
funciones con comandos pero sin una lectura suficiente y operaciones que no son
de usuario final. La interfaz no simula respuestas faltantes.

Los cambios posteriores a esta línea base se registran en el [seguimiento de remediación](remediation-roadmap-2026-11-24.md#seguimiento-de-ejecución).

## Nuevas capacidades conectadas al frontend

- Programación de vigencias de precios, historial paginado y cancelación de
  precios futuros por lista/producto.
- Reglas comerciales de descuento retiradas de interfaz, API y persistencia
  mediante V32; se mantienen los descuentos manuales de pedidos.
- Inventario de producto único conectado a lecturas, ajustes y movimientos.
- Pedidos conectados al stock único; el contrato no recibe ubicación.
- Roles de usuarios visibles como datos de solo lectura.

## Proyecciones P1 integradas en `f92c408`

- **Deudas por cliente y pago a deuda específica:** `GET
  /api/customers/{customerId}/debts` entrega saldos abiertos paginados por ID;
  la API permite imputación por venta además de FIFO. La pantalla independiente
  `PaymentsPage` se retiró de la navegación y `/payments` redirige a Pedidos
  desde el 2026-10-07. La API requiere
  `SALE_PAYMENT` o `ADMIN_ALL` y aplica alcance seller. Contrato:
  [`read-projections.md`](../api/read-projections.md).
- **Detalle y devolución de venta:** `GET /api/sales/{saleId}` devuelve IDs y
  cantidades reales por línea, incluyendo lo ya devuelto. La UI registra el
  retorno y refresca venta/inventario. El comando repone stock en el depósito
  original; no reembolsa pagos ni acredita la cuenta corriente. Ver
  [`sale-returns.md`](../api/sale-returns.md).
- **Historial de entrega:** el detalle del pedido incluye intentos ordenados,
  observaciones, resultado, actor y fecha; `OrderDetailPage` los presenta.
- **Lectura de auditoría:** `GET /api/audit` y `/admin/audit` exponen búsqueda
  y paginación en URL para administradores (`ADMIN_ALL`).

## Proyecciones que aún faltan o son parciales

- **Estado de cuenta integral del cliente:** ahora se consultan deudas abiertas
  por cliente, pero todavía no hay una vista cronológica completa de débitos,
  créditos, pagos y ventas cerradas.
- **Edición de pedidos con descuentos/listas múltiples:** el detalle ya expone el
  porcentaje/regla de descuento general y los descuentos/reglas por línea, pero
  la UI todavía limita la edición a pedidos confirmados sin descuentos y con una
  sola lista entre líneas. Falta diseñar cómo preservar y volver a resolver reglas
  automáticas al editar; el detalle tampoco expone una lista de precios de cabecera.
- **Reasignación de clientes y pedidos por vendedor:** `/sellers` permite
  reasignar toda la cartera de un vendedor a otro activo y pedidos `CONFIRMED`.
  La selección parcial por checkbox sigue acotada a filtros generales sin
  endpoint dedicado de clientes por `sellerId` con búsqueda separada.
- **Dashboard de vendedor:** `/api/dashboard` requiere `ADMIN_ALL`; vendedores se
  dirigen al listado de pedidos, ya que no existe proyección de dashboard con
  alcance vendedor.
## Capacidades implementadas y límites vigentes en frontend

- **Roles, permisos, compras, gastos, mermas y comisiones:** `/admin/roles`,
  `/admin/users`, `/supplier-orders`, `/expenses`, `/shrinkages`,
  `/admin/commission-plans` y `/commission-settlements` están conectados a sus
  respectivos contratos REST.
- **Ajustes para usuario solo con `STOCK_ADJUST`:** las lecturas de inventario y
  los permisos de página actuales limitan el acceso, por lo que esos usuarios no pueden abrir la
  pantalla frontend aunque el comando permita `STOCK_ADJUST`.

## No existe soporte backend suficiente

- **Dashboard global de outbox/worker:** solo se puede consultar el estado de una
  solicitud de notificación individual.
- **Corrección o reversión de pagos:** no existe comando de pago correctivo.
- **Configuración de proveedor Email/WhatsApp:** URLs/tokens se cargan como
  variables del entorno; la UI puede solicitar y consultar envíos, pero no
  gestionar credenciales/configuración.

## Operación, no interfaz de usuario

- El detalle del estado de CI, workflows (`backend-postgres.yml`,
  `frontend-ci.yml`, `docker-build.yml`), cobertura y remediación se registra
  en [`remediation-roadmap-2026-11-24.md`](remediation-roadmap-2026-11-24.md#seguimiento-de-ejecución).
- Operar el proveedor webhook y sus secretos en el despliegue.

## Cobertura frontend aún incompleta

- El flujo comercial tiene E2E Playwright en escritorio y móvil, documentado en
  [`commercial-e2e.md`](commercial-e2e.md). Aún falta una auditoría visual global
  automatizada.
- Los listados principales sincronizan búsqueda, filtros y página con la URL y
  consultan páginas al backend. El E2E comprueba navegación móvil y ausencia de
  desbordamiento horizontal.
- La accesibilidad se verificó de forma dirigida en flujos críticos (teclado y
  foco visible); falta una auditoría automatizada global.
