# Checklist de implementacion

Estado de referencia: 2026-09-23.

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
- [x] Refresh tokens rotativos y revocables.
- [x] Invitacion, activacion y revocacion administrativa de sesiones.

### Usuarios, vendedores y clientes

- [x] Alta y consulta administrativa de usuarios.
- [x] Creacion de perfiles seller durante el alta.
- [x] CRUD de clientes y baja logica.
- [x] Asignacion de vendedor y lista de precios.
- [x] CUIT opcional y unico cuando se informa.
- [x] CRUD completo de vendedores existentes.
- [x] Reasignacion masiva de clientes y pedidos.

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
- [ ] CRUD completo de marcas y categorias.

### Inventario

- [x] Saldos actuales y movimientos append-only.
- [x] Ajustes manuales con delta firmado y lock pesimista.
- [x] Movimientos `SALE` al confirmar pedidos.
- [ ] Devoluciones `RETURN`.
- [ ] Edicion de pedidos confirmados con deltas compensatorios.
- [ ] Multi-deposito.

### Pedidos, ventas, pagos y cuenta corriente

- [x] Confirmacion atomica de pedidos.
- [x] Snapshots de producto, lista, precio y descuentos.
- [x] Pagos `CASH`, `BANK_TRANSFER` y cuenta corriente.
- [x] Pagos parciales, combinados e idempotencia.
- [x] Intentos de entrega y cancelacion con rollback de stock.
- [ ] Registrar cobros durante la entrega.
- [ ] Registrar numero opcional de transferencia durante la entrega.
- [ ] Enviar automaticamente el saldo restante a la cuenta corriente durante la entrega.
- [ ] Aplicacion de pagos por deuda especifica o FIFO.
- [ ] Limite de credito y advertencias auditadas.

### Documentos, notificaciones y operacion

- [x] PDF A4 bajo demanda con ownership.
- [ ] Tickets y otros formatos.
- [ ] Outbox transaccional y worker con reintentos.
- [ ] WhatsApp y email configurables.
- [ ] Metricas, logs estructurados y CI de integracion.
- [ ] Backups, restauracion y procedimiento de rollback.

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

1. Completar pricing por lista y la regla de costo/precios, primero en backend y
   luego en frontend.
2. Conectar el nuevo pedido a la confirmacion atomica.
3. Completar inventario, pagos, cuenta corriente y entrega.
4. Completar documentos, notificaciones y calidad operativa.
