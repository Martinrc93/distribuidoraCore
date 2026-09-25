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

## Proyecciones P1 integradas en `f92c408`

- **Deudas por cliente y pago a deuda específica:** `GET
  /api/customers/{customerId}/debts` entrega saldos abiertos paginados por ID;
  `PaymentsPage` ofrece la imputación por venta además de FIFO. Requiere
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

- **Dashboard global de outbox/worker:** solo se puede consultar el estado de una
  solicitud de notificación individual.
- **Corrección o reversión de pagos:** no existe comando de pago correctivo.
- **Configuración de proveedor Email/WhatsApp:** URLs/tokens se cargan como
  variables del entorno; la UI puede solicitar y consultar envíos, pero no
  gestionar credenciales/configuración.

## Operación, no interfaz de usuario

- Esta rama parte de `origin/main` `03586fd`; la implementación está en
  `f92c408`. El [run 36073594320](https://github.com/Martinrc93/distribuidoraCore/actions/runs/36073594320)
  falló por una definición duplicada de `jwtAuthenticationFilter`, causada por
  clases compiladas obsoletas. `.github/workflows/backend-postgres.yml` ahora
  ejecuta `clean test` y escucha `main` en push y pull request. Falta confirmar
  el resultado de Actions sobre esta rama/PR.
- Backups programados, copia externa y ArchUnit constan como verificados en
  `backend-checklist.md`; repetirlos solo si el entorno o el código relevante
  cambian.
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
