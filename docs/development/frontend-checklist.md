# Frontend Development Checklist

Estado actualizado: 2026-09-25

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

- Referencia obligatoria para cambios de interfaz: [guía de estilos y patrones de interfaz](ui-style-guide.md). Describe los componentes reales, sus variantes y los requisitos para nuevas interfaces.

- [x] Tailwind CSS 4 integrado con Vite y Vitest; patrones existentes migrados a `@apply` con valores exactos y sin Preflight.
- [x] Layout responsive desktop/mobile.
- [x] Tablas transformadas a cards en mobile; listados con búsqueda/filtros y paginación conectados a parámetros de URL.
- [x] Estados de carga y error para consultas principales.
- [x] Componentes reutilizables de botones, paneles, badges y tablas; paginación compuesta por pantalla con estilos comunes.
- [x] Feedback de mutaciones con mensajes de éxito y errores accionables en flujos implementados.
- [x] Confirmación explícita para bajas lógicas, cancelaciones y acciones irreversibles implementadas.
- [x] Filtros y paginación persistidos en URL en clientes, pedidos, ventas, pagos, productos, inventario, depósitos, listas/precios/historial, descuentos, usuarios, vendedores y auditoría.
- [~] Estados vacíos específicos en los módulos conectados; completar en las vistas pendientes.
- [x] Accesibilidad revisada en flujos principales: foco visible, labels, navegación por teclado, roles/estados accesibles y mensajes de éxito/error.
- [~] Tests de componentes, mutaciones y navegación con React Testing Library; no incluye aún todos los módulos ni E2E.
- [x] Tests E2E con Playwright para login y flujo comercial en Chromium desktop y Pixel 7 mobile.

## Customers

- [x] Listado de clientes disponible para vendedores solo con sus clientes asignados, sin acciones administrativas; ficha y administración exclusivas de `ADMIN_ALL`.

- [x] Listado real desde `/api/customers`.
- [x] Alta de cliente con formulario controlado.
- [x] Edición de cliente.
- [x] Invalidación de query después de guardar.
- [x] Asignación de vendedor.
- [x] Asignación de lista de precios.
- [x] Baja lógica/reactivación desde la UI.
- [ ] Vista de detalle de cliente con ventas, pagos y cuenta corriente (falta consulta dedicada por `customerId`).

## Proveedores

- [x] Pantalla `/suppliers` y menú Catálogo disponibles solo para `ADMIN_ALL`.
- [x] Alta y edición: nombre obligatorio, teléfono/mail/dirección opcionales.
- [x] Listado, búsqueda y paginación reales; estado de consulta en la URL.
- [x] Feedback de guardado, reintento de consultas y descarte confirmado sin perder datos ante errores.
- [x] Pruebas de componentes y flujo con API simulada en desktop y mobile.

## Catalog Y Pricing

- [x] Secciones de productos, marcas/categorías y listas de precios exclusivas de `ADMIN_ALL`, sin menú ni acceso por URL para vendedores; se conservan los datos necesarios para crear pedidos.

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
- [x] Programar vigencias de precios por fecha efectiva; consultar historial paginado y cancelar vigencias futuras.
- [x] Retirar la sección de reglas de descuento; las listas conservan precios e historial.
- [x] Manejar `403`, `404` y `409` con mensajes accionables.

## Inventory

- [x] Consulta real de saldos e inventario.
- [x] Historial de movimientos por producto.
- [x] Formulario de ajuste manual con cantidades en múltiplos de `0.5` y motivo.
- [x] Advertencia de saldo negativo.
- [x] Ocultar ajustes para usuarios sin `STOCK_ADJUST`.
- [x] Confirmación antes de aplicar ajuste.
- [x] Feedback de éxito e invalidación de consultas.
- [x] Mostrar una lista única paginada y buscable de stock por producto.
- [x] Ajustar el saldo único; movimientos y payloads no incluyen ubicación.

## Orders

- [x] Listado real de pedidos con búsqueda y estado.
- [x] Formulario local sin borrador persistido y selección de cliente/lista/productos.
- [x] Mostrar stock disponible y resolver precios con `/api/pricing/resolve`.
- [x] Preview de líneas/descuentos sin sustituir el backend.
- [x] Mostrar descuentos y precios manuales solo a `ADMIN_ALL`.
- [x] Mantener fija la lista asignada al cliente para vendedores al crear pedidos; validar en la API que solo `ADMIN_ALL` pueda elegir otra lista.
- [x] `idempotencyKey` por intento, bloqueo de doble envío y retry con clave/payload iguales.
- [x] Crear pedidos sin subtítulo ni selector de depósito; todos los usuarios usan el stock único del backend.
- [x] Confirmación contra `/api/orders/confirm`; render de números, total, cobrado, saldo y warning de crédito.
- [x] Detalle de pedido/venta con snapshots, pagos y ledger asociado a la venta.
- [x] Edición administrativa de pedidos confirmados con una sola lista; preserva porcentajes manuales de línea y pedido. Las reglas automáticas fueron retiradas.
- [~] Advertencia antes de abandonar cubre navegación del navegador, no toda navegación interna.

## Sales, Payments Y Cuenta Corriente

La pantalla independiente «Pagos y deuda» está retirada para todos los usuarios
desde el 2026-10-07. `/payments` redirige a Pedidos. El listado global de pagos,
el cobro independiente con imputación FIFO o a venta específica y su resultado
ya no están disponibles como pantalla; la API conserva esas capacidades.

- [x] Listado real de ventas con total, cobrado, saldo y estado.
- [x] Mostrar únicamente ventas entregadas para vendedores, con conteos, paginación y opciones de filtros restringidos en la API; administradores conservan todos los estados.
- [x] Cobros parciales/combinados al confirmar pedido o durante la entrega.
- [x] Devolver venta desde la UI con las líneas de `saleId`.
- [ ] Corregir/revertir pago (no existe endpoint de comando).

## Administración De Usuarios

- [x] Mostrar roles devueltos por API como etiquetas de solo lectura, conservando códigos desconocidos.
- [ ] Reasignar roles y editar permisos (API disponible, funcionalidad aplazada por decisión del usuario).

## Delivery, Documents Y Notifications

- [x] Marcar pedido como `DELIVERED` y registrar cobros opcionales durante entrega.
- [x] Registrar intentos fallidos con observaciones.
- [x] Cancelar pedido confirmado con confirmación y permiso administrativo.
- [~] Cancelación ejecutada por backend; el detalle no expone movimientos para verificar rollback de stock.
- [x] Descargar PDF A4 y ticket bajo demanda.
- [x] Solicitar notificación Email/WhatsApp y consultar estado individual.
- [x] Ver historial de intentos de entrega en la respuesta/detalle del pedido.
- [ ] Mostrar estado global de outbox/worker (no existe endpoint operativo).

## Calidad Y Entrega

- [x] `npm run build` funcional.
- [x] Frontend servido por Nginx en Compose.
- [x] Proxy `/api` hacia backend.
- [x] Tests unitarios de API client, formularios y flujos implementados.
- [~] Contratos TypeScript alineados con flujos implementados; sin Zod.
- [ ] Validación de formularios con React Hook Form + Zod según la arquitectura documentada.
- [x] E2E login -> crear cliente/producto con precio -> pedido -> confirmar -> ver pedido/venta.
- [x] Diseño responsive actualizado; Playwright mobile comprueba navegación y ancho de documento.
- [~] Errores/permisos cubiertos en pruebas de componente; falta auditoría visual completa.

## Siguiente Orden Recomendado

1. [x] Conectar `Nuevo pedido` al endpoint de confirmación atómica.
2. [x] Crear UI de Pricing, marcas/categorías y precios por lista.
3. [x] Completar edición/baja de productos y ajustes de inventario.
4. [~] Completar ventas y cuenta corriente; cobros durante entrega y devolución con líneas disponibles, aún falta vista integral de estado de cuenta. La pantalla independiente de pagos está retirada.
5. [~] Implementar entrega, cancelación, documentos y notificaciones; historial de intentos completado, dashboard global de outbox sigue pendiente.
6. [x] Conectar vigencias/reglas de precio y flujos de inventario de stock único al frontend.
7. [x] Mostrar rol actual en usuarios; conservar edición de roles/permisos como pendiente aplazado.
8. [x] Tests Vitest de API client y flujos principales; E2E comercial desktop/mobile ejecutado.

## Criterio De Cierre Frontend

Una funcionalidad se marca `[x]` solo cuando la UI usa el endpoint real, respeta permisos del backend, invalida/actualiza queries, muestra carga/vacío/error/éxito, funciona en desktop y mobile, y tiene cobertura de test apropiada.
