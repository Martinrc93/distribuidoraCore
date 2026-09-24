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
- [~] Manejo de sesión expirada: limpia token en `401`, falta redirección/refresh explícito.
- [ ] Mostrar permisos actuales desde claims/usuario y ocultar acciones por permiso.
- [ ] Refresh/revocación de sesión cuando el backend esté disponible.

## Componentes Y UX Transversal

- [x] Layout responsive desktop/mobile.
- [x] Tablas paginadas y transformadas a cards en mobile.
- [x] Estados de carga y error para consultas principales.
- [x] Componentes reutilizables de botones, paneles, badges, tablas y paginación.
- [~] Feedback de mutaciones: existe feedback de error en formularios, falta feedback global de éxito.
- [ ] Confirmación explícita para bajas lógicas y acciones irreversibles.
- [ ] Filtros y paginación persistidos en URL.
- [ ] Estados vacíos específicos por módulo.
- [ ] Accesibilidad: foco, labels, navegación de teclado y mensajes para lectores.
- [ ] Tests de componentes, mutaciones y navegación con React Testing Library.
- [ ] Tests E2E con Playwright para login y flujos comerciales.

## Customers

- [x] Listado real desde `/api/customers`.
- [x] Alta de cliente con formulario controlado.
- [x] Edición de cliente.
- [x] Invalidación de query después de guardar.
- [x] Asignación de vendedor.
- [x] Asignación de lista de precios.
- [x] Baja lógica/reactivación desde la UI.
- [ ] Vista de detalle de cliente con ventas, pagos y cuenta corriente.

## Catalog Y Pricing

- [x] Listado real de productos desde `/api/products`.
- [x] Alta básica de producto.
- [x] Edición de producto.
- [x] Baja/reactivación de producto.
- [ ] Pantalla de listas de precios.
- [ ] Crear/renombrar/activar/desactivar lista.
- [ ] Editar precios por producto dentro de una lista.
- [ ] Resolver y mostrar lista/precio seleccionado para un cliente.
- [x] Manejar `403`, `404` y `409` con mensajes accionables.

## Inventory

- [x] Consulta real de saldos e inventario.
- [~] Historial de movimientos: endpoint backend disponible, vista de detalle pendiente.
- [ ] Selector y administración de depósitos con balances por depósito (`ADMIN_ALL`).
- [ ] Formulario de transferencia entre depósitos (`STOCK_ADJUST`), saldo disponible y feedback de error/éxito.
- [ ] Elegir depósito al confirmar un pedido y conservar la selección en reintentos seguros.
- [ ] Formulario de ajuste manual con cantidad en múltiplos de `0.5`.
- [ ] Mostrar advertencia de saldo negativo.
- [ ] Ocultar ajuste manual para usuarios sin `STOCK_ADJUST`.
- [ ] Confirmación antes de aplicar ajuste.
- [ ] Feedback de éxito y actualización inmediata de la query.

## Orders

- [~] Listado de pedidos real.
- [~] Pantalla "Nuevo pedido" visual: falta conectar al comando real.
- [ ] Formulario local sin borrador persistido.
- [ ] Selección de cliente y vendedor asignado.
- [ ] Selección explícita de lista de precios.
- [ ] Búsqueda de productos y stock disponible.
- [ ] Cálculo visual de líneas/descuentos como preview, sin reemplazar backend.
- [ ] Campo `idempotencyKey` generado por cada intento de confirmación.
- [ ] Confirmación contra `/api/orders/confirm`.
- [ ] Prevenir doble click mientras confirma.
- [ ] Mostrar respuesta con número de pedido, venta, pagado y saldo.
- [ ] Reintentar la misma confirmación usando la misma clave.
- [ ] Mostrar errores de stock, precio, permisos y conflicto de idempotencia.
- [ ] Advertir al abandonar un pedido con cambios no confirmados.

## Sales, Payments Y Cuenta Corriente

- [~] Listado real de ventas.
- [~] Listado real de pagos.
- [ ] Detalle de venta con snapshots, pagos y saldo.
- [ ] Registrar pago parcial o combinado.
- [ ] Seleccionar `CASH`, `BANK_TRANSFER` o `CUSTOMER_ACCOUNT`.
- [ ] Mostrar deuda y saldo actualizado del cliente.
- [ ] Preparar UI para aplicación FIFO cuando el backend lo implemente.

## Delivery, Documents Y Notifications

- [ ] Marcar pedido/venta como `DELIVERED`.
- [ ] Registrar intentos de entrega y observaciones.
- [ ] Cancelar venta con confirmación y permisos.
- [ ] Verificar reversión de stock después de cancelación.
- [ ] Descargar/imprimir documentos bajo demanda.
- [ ] Compartir comprobante mediante WhatsApp/link configurable.
- [ ] Mostrar estado de outbox/reintentos cuando exista backend.

## Calidad Y Entrega

- [x] `npm run build` funcional.
- [x] Frontend servido por Nginx en Compose.
- [x] Proxy `/api` hacia backend.
- [~] Tests unitarios: existen tests de app/componentes, falta cobertura de API client y flujos.
- [ ] Contratos TypeScript/Zod alineados con DTOs backend.
- [ ] Validación de formularios con React Hook Form + Zod según la arquitectura documentada.
- [ ] E2E login -> crear cliente -> crear pedido -> confirmar -> ver venta.
- [ ] Pruebas mobile de navegación, tablas y formularios.
- [ ] Auditoría visual de estados de error/autorización.

## Siguiente Orden Recomendado

1. Conectar `Nuevo pedido` al endpoint de confirmación atómica.
2. Crear UI de Pricing y asignación de lista a cliente.
3. Completar edición/baja de productos y ajustes de inventario.
4. Completar detalle de venta, pagos y cuenta corriente.
5. Implementar entrega, cancelación y documentos.
6. Agregar tests de API client y E2E.

## Criterio De Cierre Frontend

Una funcionalidad se marca `[x]` solo cuando la UI usa el endpoint real, respeta permisos del backend, invalida/actualiza queries, muestra carga/vacío/error/éxito, funciona en desktop y mobile, y tiene cobertura de test apropiada.
