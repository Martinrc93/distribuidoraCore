# Checklist de implementacion

Estado de referencia: 2026-09-24.

Este documento separa el avance funcional del backend y del frontend. Una
funcionalidad se marca como implementada solo cuando esta verificada en la capa
correspondiente. El detalle historico y los criterios de cierre se mantienen en:

- `backend-checklist.md`
- `frontend-checklist.md`

## Backend

### Plataforma y seguridad

- [x] Spring Boot modular con Java 21.
- [x] PostgreSQL, Flyway y schemas por modulo.
- [x] Docker Compose, Actuator y health checks.
- [x] `X-Request-Id` y auditoria append-only.
- [x] Login con Argon2, JWT y bloqueo por intentos fallidos.
- [x] Roles `ADMIN` y `SELLER` con permisos persistidos.
- [x] Autorizacion `@PreAuthorize` y ownership de vendedor.
- [x] Refresh tokens rotativos con bloqueo por token, detección de reuso y revocación de access/refresh tokens por versión de sesión (tests Maven y PostgreSQL funcionales pasan).
- [x] Invitacion y activacion con token de un solo uso (30 minutos); la activacion requiere login posterior.
- [x] Bloqueo y revocación administrativa inmediata de sesiones (tests Maven y PostgreSQL funcionales pasan).

### Usuarios, vendedores y clientes

- [x] Alta y consulta administrativa de usuarios.
- [x] Creacion de perfiles seller durante el alta.
- [x] CRUD de clientes y baja logica.
- [x] Asignacion de vendedor y lista de precios.
- [x] CUIT opcional y unico cuando se informa.
- [x] CRUD completo de vendedores existentes.
- [x] Reasignacion masiva de clientes y pedidos `CONFIRMED`, preservando pedidos históricos (tests Maven y PostgreSQL funcionales pasan).

### Catalogo y precios

- [x] Alta, edicion y baja logica de productos.
- [x] SKU unico y costos no negativos.
- [x] Listas de precios con maximo de diez.
- [x] Precios por producto y lista con `NUMERIC(19,4)`.
- [x] Resolucion por lista explicita, lista del cliente y fallback documentado.
- [x] Eliminar el precio almacenado directamente en la tabla de productos (migración V11).
- [x] Usar exclusivamente `catalog.product_prices` como fuente de precios (esquema, seed, comandos y lecturas migrados).
- [x] Editar costo sin precio cuando no supera ninguna lista activa.
- [x] Exigir nuevos precios para todas las listas afectadas cuando el costo las supera.
- [x] Actualizar costo y precios afectados en una sola transaccion.
- [x] Exponer las listas afectadas en el error de validacion.
- [x] Eliminar historial de costos del alcance funcional.
- [x] CRUD administrativo de marcas y categorías y asociación opcional en productos (tests Maven y PostgreSQL funcionales pasan).

### Inventario

- [x] Saldos actuales y movimientos append-only.
- [x] Ajustes manuales con delta firmado y lock pesimista.
- [x] Movimientos `SALE` al confirmar pedidos.
- [x] Reversión neta `SALE_CANCELLATION` al cancelar pedidos confirmados sin pagos, incluyendo movimientos compensatorios de ediciones anteriores.
- [x] Devoluciones `RETURN` parciales con saldo por línea, lock por venta y reintegro de stock transaccional; ver [contrato de API](../api/sale-returns.md).
- [x] Edición administrativa de pedido/venta `CONFIRMED` con reemplazo de snapshots, deltas compensatorios, pagos preservados y conciliación de ledger; ver [contrato de API](../api/order-edits.md).
- [ ] Multi-deposito.

### Pedidos, ventas, pagos y cuenta corriente

- [x] Confirmacion atomica de pedidos.
- [x] Snapshots de producto, lista, precio y descuentos.
- [x] Pagos `CASH`, `BANK_TRANSFER` y cuenta corriente.
- [x] Pagos parciales, combinados e idempotencia.
- [x] Intentos de entrega y cancelacion con rollback de stock.
- [x] Registrar cobros `CASH`/`BANK_TRANSFER` durante la entrega.
- [x] Guardar y exponer la referencia opcional de transferencia.
- [x] Dejar el saldo no cobrado en cuenta corriente con un asiento `CREDIT` por lo recibido.
- [x] Aplicacion de pagos por deuda especifica o FIFO, con endpoint, autoridad `SALE_PAYMENT`, ledger y bloqueo transaccional; ver [contrato de API](../api/account-payments.md).
- [x] Límite de crédito global configurable y no bloqueante, advertencia en confirmación y evento auditado; ver [contrato](../api/credit-limit.md).

### Documentos, notificaciones y operacion

- [x] PDF A4 bajo demanda con ownership.
- [x] Ticket PDF de 80 mm generado desde snapshots persistidos, bajo demanda.
- [x] Outbox transaccional para confirmaciones, worker con leases, backoff y despacho idempotente; ver `docs/architecture/integration-architecture.md`.
- [x] Solicitudes idempotentes email/WhatsApp mediante webhooks configurables; auditoría de solicitud y cada intento.
- [x] Métricas HTTP/outbox, logs ECS JSON y propagación segura de request ID.
- [x] Backup cifrado, retención definida, restauración automatizada y procedimiento de rollback documentado.
- [~] Workflow CI PostgreSQL configurado; pendiente primera ejecución remota. Programación diaria y copia externa de backups requieren activación operativa.
- [x] Purga de destinatarios de notificaciones y eventos terminales tras 90 días por defecto.
- [ ] ArchUnit o Spring Modulith para validar límites modulares.

## Frontend

### Base y seguridad

- [x] React, TypeScript, Vite, Router y TanStack Query.
- [x] Cliente HTTP autenticado y shell protegido.
- [x] Layout responsive desktop/mobile.
- [x] Estados de carga, vacio y error principales.
- [~] Manejo de sesion expirada sin refresh/revocacion completa.
- [ ] Ocultar acciones segun permisos actuales.
- [ ] Accesibilidad completa de foco, labels y teclado.

### Usuarios, vendedores y clientes

- [x] Listado, alta y edicion de clientes.
- [x] Asignacion de vendedor y lista de precios.
- [x] Baja logica y reactivacion.
- [ ] Administracion completa de usuarios y vendedores.
- [ ] Detalle de cliente con ventas, pagos y cuenta corriente.

### Catalogo y precios

- [x] Listado, alta, edicion y baja logica de productos.
- [x] Validacion local de datos basicos y mensajes HTTP.
- [ ] Quitar el campo y la columna de precio general del producto.
- [ ] Mostrar precios exclusivamente por lista.
- [ ] Crear y editar precios por producto/lista.
- [ ] Editar costo sin pedir precios cuando no hay listas afectadas.
- [ ] Mostrar automaticamente las listas afectadas cuando el nuevo costo las supera.
- [ ] Enviar costo y nuevos precios afectados en la misma operacion.
- [ ] Mostrar errores de listas faltantes o precios menores al costo.
- [ ] Pantalla completa de administracion de listas.

### Inventario

- [x] Consulta de saldos.
- [~] Consulta de movimientos sin vista de detalle completa.
- [ ] Formulario de ajuste manual.
- [ ] Permisos, confirmacion y advertencia de saldo negativo.

### Pedidos y ventas

- [~] Listados reales de pedidos, ventas y pagos.
- [ ] Conectar nuevo pedido con `/api/orders/confirm`.
- [ ] Seleccionar cliente, lista y productos con stock.
- [ ] Preview de descuentos y totales sin reemplazar el backend.
- [ ] Idempotency key, bloqueo de doble envio y reintento seguro.
- [ ] Detalle de venta con snapshots, pagos y saldo.
- [ ] Registrar pagos parciales y combinados.

### Entrega, documentos y calidad

- [ ] Marcar pedido/venta como entregado.
- [ ] Registrar intentos fallidos y observaciones.
- [ ] Cobrar durante la entrega con efectivo o transferencia.
- [ ] Capturar numero opcional de transferencia.
- [ ] Descargar e imprimir documentos desde la interfaz.
- [ ] Compartir comprobantes por WhatsApp/link configurable.
- [x] Build y proxy `/api` funcionando.
- [~] Tests de componentes parciales.
- [ ] Tests de API client y flujos E2E.
- [ ] Verificacion mobile de formularios y tablas.

## Orden sugerido

1. [x] Completar cobros durante la entrega: método, referencia opcional de transferencia y resto a cuenta corriente.
2. [x] Imputar pagos a una deuda específica o mediante FIFO.
3. [x] Configurar límite de crédito global y advertencias auditadas por exceso.
4. [x] Crear outbox transaccional y worker con reintentos e idempotencia.
5. [x] Añadir tickets y notificaciones configurables de WhatsApp/email con auditoría.
6. [x] Implementar endurecimiento operativo: CI PostgreSQL, métricas/logs estructurados, backup/restauración, rollback y retención/purga. Falta activar la ejecución remota/programada y configurar réplica externa.

Estado de verificación (2026-09-24): 301 tests, 0 fallos, 0 errores y 0
omitidos; suite ejecutada con PostgreSQL 16.4 (Flyway V1–V20, 18 casos
funcionales de integración). La restauración cifrada y el rechazo de alteración
HMAC se comprobaron en una base descartable, eliminada al finalizar.

### Próximas tareas backend

- [ ] Confirmar primera ejecución de CI en GitHub Actions.
- [ ] Activar la tarea diaria de backup y configurar copia externa.
- [ ] Añadir ArchUnit o Spring Modulith.
- [ ] Completar administración de roles/permisos y aumentar cobertura HTTP.
