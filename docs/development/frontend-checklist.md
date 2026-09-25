# Frontend Development Checklist

Estado actualizado: 2026-09-24

## Cómo Leerlo

- `[x]` Implementado y verificado.
- `[ ]` Pendiente.
- `[~]` Parcial o requiere integración adicional.
- Las pantallas deben contemplar carga, vacío, error, autorización, feedback de mutación y mobile.

## Base De La Aplicación

- [x] React + TypeScript + Vite.
- [x] React Router y shell autenticado.
- [x] TanStack Query para server state.
- [x] Cliente HTTP autenticado con token en `sessionStorage`.
- [x] Login real contra `/api/auth/login`.
- [x] Manejo de sesión expirada: una renovación compartida por solicitudes concurrentes, un retry y limpieza de tokens si refresh falla.
- [x] Mostrar acciones administrativas según autoridades del JWT y dejar la autorización final al backend.
- [x] Refresh/revocación de sesión con `/api/auth/refresh` y `/api/auth/logout`.

## Componentes Y UX Transversal

- [x] Layout responsive desktop/mobile.
- [~] Tablas transformadas a cards en mobile; varios listados muestran el primer bloque de 20 sin controles de página activos.
- [x] Estados de carga y error para consultas principales.
- [x] Componentes reutilizables de botones, paneles, badges, tablas y paginación.
- [x] Feedback de mutaciones con mensajes de éxito y errores accionables en flujos implementados.
- [x] Confirmación explícita para bajas lógicas, cancelaciones y acciones irreversibles implementadas.
- [ ] Filtros y paginación persistidos en URL.
- [~] Estados vacíos específicos en los módulos conectados; completar en las vistas pendientes.
- [ ] Accesibilidad: foco, labels, navegación de teclado y mensajes para lectores.
- [~] Tests de componentes, mutaciones y navegación con React Testing Library; no incluye aún todos los módulos ni E2E.
- [ ] Tests E2E con Playwright para login y flujos comerciales.

## Customers

- [x] Listado real desde `/api/customers`.
- [x] Alta de cliente con formulario controlado.
- [x] Edición de cliente.
- [x] Invalidación de query después de guardar.
- [x] Asignación de vendedor.
- [x] Asignación de lista de precios.
- [x] Baja lógica/reactivación desde la UI.
- [ ] Vista de detalle de cliente con ventas, pagos y cuenta corriente (falta consulta dedicada por `customerId`).

## Catalog Y Pricing

- [x] Listado real de productos desde `/api/products`.
- [x] Alta básica de producto.
- [x] Edición de producto.
- [x] Baja/reactivación de producto.
- [x] Pantalla de listas de precios.
- [x] Crear/renombrar/activar/desactivar lista.
- [x] Editar precios por producto dentro de una lista.
- [x] Resolver y mostrar lista/precio seleccionado para un cliente en el flujo de pedidos.
- [x] Quitar el precio general del producto; gestionar precios por lista.
- [x] Editar costo y solicitar reemplazo solo para listas activas afectadas.
- [x] Administrar marcas/categorías y asociarlas opcionalmente al producto.
- [x] Manejar `403`, `404` y `409` con mensajes accionables.

## Inventory

- [x] Consulta real de saldos e inventario.
- [x] Historial de movimientos por producto.
- [x] Formulario de ajuste manual con cantidades en múltiplos de `0.5` y motivo.
- [x] Advertencia de saldo negativo.
- [x] Ocultar ajustes para usuarios sin `STOCK_ADJUST`.
- [x] Confirmación antes de aplicar ajuste.
- [x] Feedback de éxito e invalidación de consultas.

## Orders

- [x] Listado real de pedidos con búsqueda y estado.
- [x] Formulario local sin borrador persistido y selección de cliente/lista/productos.
- [x] Mostrar stock disponible y resolver precios con `/api/pricing/resolve`.
- [x] Preview de líneas/descuentos sin sustituir el backend.
- [x] Mostrar descuentos y precios manuales solo a `ADMIN_ALL`.
- [x] `idempotencyKey` por intento, bloqueo de doble envío y retry con clave/payload iguales.
- [x] Confirmación contra `/api/orders/confirm`; render de números, total, cobrado, saldo y warning de crédito.
- [x] Detalle de pedido/venta con snapshots, pagos y ledger asociado a la venta.
- [~] Edición administrativa disponible si no hay descuentos guardados y se usa una sola lista; GET no expone el porcentaje de descuento general original.
- [~] Advertencia antes de abandonar cubre navegación del navegador, no toda navegación interna.

## Sales, Payments Y Cuenta Corriente

- [x] Listado real de ventas con total, cobrado, saldo y estado.
- [x] Listado real de pagos con referencia opcional de transferencia.
- [x] Cobros parciales/combinados al confirmar pedido o durante la entrega.
- [x] Pagos `CASH`/`BANK_TRANSFER` a cuenta corriente con imputación FIFO.
- [x] Mostrar resultado de imputación y saldos anterior/actualizado.
- [ ] Aplicar pago a una deuda específica (falta consulta de deudas por `customerId`).
- [ ] Devolver venta desde la UI (falta lectura de líneas por `saleId`).
- [ ] Corregir/revertir pago (no existe endpoint de comando).

## Delivery, Documents Y Notifications

- [x] Marcar pedido como `DELIVERED` y registrar cobros opcionales durante entrega.
- [x] Registrar intentos fallidos con observaciones.
- [x] Cancelar pedido confirmado con confirmación y permiso administrativo.
- [~] Cancelación ejecutada por backend; el detalle no expone movimientos para verificar rollback de stock.
- [x] Descargar PDF A4 y ticket bajo demanda.
- [x] Solicitar notificación Email/WhatsApp y consultar estado individual.
- [ ] Ver historial de intentos de entrega (no está en la respuesta de detalle).
- [ ] Mostrar estado global de outbox/worker (no existe endpoint operativo).

## Calidad Y Entrega

- [x] `npm run build` funcional.
- [x] Frontend servido por Nginx en Compose.
- [x] Proxy `/api` hacia backend.
- [x] Tests unitarios de API client, formularios y flujos implementados.
- [~] Contratos TypeScript alineados con flujos implementados; sin Zod.
- [ ] Validación de formularios con React Hook Form + Zod según la arquitectura documentada.
- [ ] E2E login -> crear cliente -> crear pedido -> confirmar -> ver venta.
- [~] Diseño responsive actualizado; faltan pruebas automatizadas mobile.
- [~] Errores/permisos cubiertos en pruebas de componente; falta auditoría visual completa.

## Siguiente Orden Recomendado

1. [x] Conectar `Nuevo pedido` al endpoint de confirmación atómica.
2. [x] Crear UI de Pricing, marcas/categorías y precios por lista.
3. [x] Completar edición/baja de productos y ajustes de inventario.
4. [~] Completar ventas/pagos y cuenta corriente; faltan deuda específica y devoluciones con líneas.
5. [~] Implementar entrega, cancelación, documentos y notificaciones; falta historial de intentos.
6. [~] Tests Vitest de API client y flujos principales; E2E aún pendiente.

## Criterio De Cierre Frontend

Una funcionalidad se marca `[x]` solo cuando la UI usa el endpoint real, respeta permisos del backend, invalida/actualiza queries, muestra carga/vacío/error/éxito, funciona en desktop y mobile, y tiene cobertura de test apropiada.
