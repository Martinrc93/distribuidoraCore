# Estado De Funcionalidades Del Backend

Estado relevado: 2026-09-24.

Este documento resume el estado real del backend según los módulos, migraciones,
controllers y tests existentes. `[x]` significa implementado y verificado;
`[~]` significa parcial o con endurecimiento pendiente; `[ ]` significa que
 todavía no está implementado.

## Implementado

### Plataforma

- [x] Monolito modular Spring Boot con Java 21.
- [x] PostgreSQL 16 con Flyway y schemas por módulo.
- [x] Docker Compose para base de datos, backend y frontend.
- [x] Actuator con health/readiness.
- [x] Filtro `X-Request-Id` y propagación del request ID.
- [x] Configuración local separada de los defaults no locales.

### Identidad, autenticación y autorización

- [x] Login con email y contraseña.
- [x] Contraseñas protegidas con Argon2.
- [x] JWT stateless con expiración configurable.
- [x] Refresh tokens con rotación, bloqueo por fila y detección de reuso.
- [x] Bloqueo, desbloqueo y revocación administrativa de sesiones mediante versión de sesión que invalida JWT anteriores.
- [x] Bloqueo automático después de tres intentos fallidos.
- [x] Auditoría de login exitoso y fallido.
- [x] Roles persistidos `ADMIN` y `SELLER`.
- [x] Permisos persistidos y cargados en las authorities del JWT.
- [x] `@PreAuthorize` en comandos administrativos y endpoints sensibles.
- [x] Alta administrativa de usuarios con contraseña temporal.
- [x] Creación atómica de perfil seller para usuarios `SELLER`.
- [x] Acceso seller restringido a clientes, pedidos, ventas, pagos, lifecycle y PDFs propios/asignados.
- [x] Acceso global administrativo mediante `ADMIN_ALL`.

### Usuarios y vendedores

- [x] Crear usuarios desde `POST /api/users`.
- [x] Consultar usuarios desde `GET /api/users` para administración.
- [x] Consultar perfiles seller para asignaciones desde `GET /api/sellers`.
- [x] Asociar clientes y pedidos con `seller_id`.
- [x] Resolver ownership del usuario actual mediante `CurrentUserAccess`.
- [x] Auditoría de creación de usuarios y perfiles seller.
- [x] Reasignación masiva de clientes y pedidos `CONFIRMED`, preservando los pedidos históricos.

### Clientes

- [x] Crear clientes.
- [x] Editar clientes.
- [x] Activar y desactivar clientes lógicamente.
- [x] Asignar vendedor.
- [x] Asignar y limpiar lista de precios.
- [x] Validar identificación fiscal única cuando se informa; el campo es opcional.
- [x] Auditar altas, modificaciones, estados y asignaciones.
- [x] Filtrar lecturas seller por clientes asignados.

### Catálogo y pricing

- [x] Crear productos.
- [x] Editar productos.
- [x] Activar y desactivar productos lógicamente.
- [x] Validar SKU único e importes no negativos.
- [x] Mantener precios con `NUMERIC(19,4)` y `BigDecimal`.
- [x] Crear, editar y cambiar estado de listas de precios.
- [x] Mantener listas iniciales `GENERAL`, `LISTA_2` y `LISTA_3`.
- [x] Limitar a diez listas y proteger la lista `GENERAL`.
- [x] Crear y editar precios por producto/lista.
- [x] Resolver precio explícito, asignado al cliente o fallback `GENERAL`.
- [x] Autorizar mutaciones de catálogo/pricing con `ADMIN_ALL`.
- [x] CRUD administrativo de marcas y categorías y referencias activas opcionales desde productos.

### Inventario

- [x] Mantener saldo actual por producto.
- [x] Registrar movimientos append-only.
- [x] Aplicar ajustes manuales con delta firmado.
- [x] Validar cantidades en múltiplos de `0.5`.
- [x] Permitir saldos negativos.
- [x] Bloquear balances con `SELECT ... FOR UPDATE`.
- [x] Registrar movimientos `SALE` al confirmar pedidos.
- [x] Auditar ajustes y referencias de movimientos.
- [x] Restringir ajustes manuales mediante `STOCK_ADJUST`.
- [x] Registrar devoluciones `RETURN` parciales con límite por cantidad vendida, bajo lock y con actualización transaccional del stock.

### Pedidos, ventas, pagos y cuenta corriente

- [x] Confirmar pedidos con `POST /api/orders/confirm`.
- [x] Ejecutar confirmación, venta, stock y pago en una transacción.
- [x] No persistir borradores.
- [x] Guardar snapshots de producto, lista, precio y descuentos.
- [x] Calcular descuentos y totales con `BigDecimal`.
- [x] Registrar pagos `CASH` y `BANK_TRANSFER`.
- [x] Registrar pagos a `CUSTOMER_ACCOUNT` mediante ledger append-only.
- [x] Soportar pagos parciales y combinados.
- [x] Aplicar idempotencia por clave, fingerprint y advisory lock PostgreSQL.
- [x] Evitar duplicados ante reintentos concurrentes.
- [x] Consultar detalle de pedido por ID y número.
- [x] Registrar intentos de entrega exitosos y fallidos.
- [x] Marcar pedido y venta como `DELIVERED`.
- [x] Cancelar ventas confirmadas sin pagos.
- [x] Revertir stock con `SALE_CANCELLATION` al cancelar.
- [x] Crear crédito de cuenta corriente al cancelar deuda pendiente.
- [x] Rechazar cancelación de ventas pagadas o estados terminales.
- [x] Editar pedidos y ventas `CONFIRMED` con `ADMIN_ALL`; reemplazar snapshots, registrar auditoría `ORDER_EDIT` y preservar pagos.
- [x] Aplicar movimientos compensatorios de inventario y ajustar el ledger/saldo del cliente según el nuevo total, en una transacción.
- [x] Cancelar una venta editada revirtiendo el efecto neto de movimientos `SALE` y `SALE_CANCELLATION` por producto.
- [x] Contrato documentado en `docs/api/order-edits.md`.
- [x] Registrar cobros `CASH`/`BANK_TRANSFER` al entregar, opcionalmente guardar la referencia de transferencia y reducir el ledger por el importe cobrado.
- [x] Exponer `transferReference` en detalle de venta y listados de pagos; el request fallido no admite cobros.
- [x] Registrar pagos de cuenta corriente por venta específica o FIFO, crear un pago/crédito por asignación y actualizar `sale.paid`/`customer.balance` con locks y auditoría; ver `docs/api/account-payments.md`.
- [x] Configurar o desactivar el límite de crédito global y advertir/auditar el saldo proyectado excedido sin bloquear la confirmación; contrato en `docs/api/credit-limit.md`.

### Documentos

- [x] Generar PDF A4 bajo demanda.
- [x] Descargar/imprimir documentos desde la API.
- [x] Usar snapshots persistidos para el contenido del documento.
- [x] No generar ni almacenar PDFs automáticamente al confirmar una venta.
- [x] Restringir documentos por ownership seller o `ADMIN_ALL`.
- [x] Mantener el documento sin estado comercial ni formas de pago.

### Auditoría y calidad

- [x] Auditoría append-only con actor, operación y request ID.
- [x] Tests unitarios de servicios y reglas de negocio.
- [x] Tests de controllers y migraciones principales.
- [x] PostgreSQL 16.4 real: Flyway V1–V20, validación JPA y pruebas funcionales opt-in de sesiones, catálogo, devoluciones, edición/cancelación, cobros durante la entrega, imputación FIFO/específica, límites de crédito, outbox y notificaciones.
- [x] Suite Maven ejecutada con PostgreSQL opt-in: 301 tests, 0 fallos, 0 errores y 0 omitidos; PostgreSQL 16.4 en V1–V20, con 18 casos funcionales de integración.

## Parcial o requiere endurecimiento

- [~] La cobertura HTTP es menor que la cobertura de servicios en algunos módulos.
- [~] El endpoint de usuarios existe, pero todavía no ofrece administración completa de roles/permisos y estados.
- [~] Workflow CI PostgreSQL 16 configurado; falta confirmar primera ejecución remota.
- [x] Logs ECS JSON con request ID en MDC, métricas HTTP de latencia e histogramas y métricas operativas del outbox.
- [x] Scripts de backup cifrado AES-256-CBC/HMAC-SHA256, retención local configurada, registro de tarea diaria y prueba de restauración/tamper en base descartable.
- [x] Procedimiento de rollback de aplicación y recuperación de PostgreSQL documentado.
- [x] Purga por defecto a 90 días de solicitudes terminales y eventos outbox; auditoría enmascarada se conserva.

## Pendiente

### Identidad y sesiones

- [x] Alta administrativa con estado `INVITED` y token de activación de un solo uso (30 minutos).
- [ ] Administración completa de roles y permisos desde endpoints dedicados.

### Vendedores

- [x] CRUD administrativo de perfiles seller y asociación con usuarios.

### Catálogo y pricing

- [ ] Historial de precios.
- [ ] Vigencias futuras de precios.
- [ ] Reglas de descuentos comerciales persistidas fuera de la confirmación.

### Inventario y modificaciones comerciales

- [x] Editar pedidos confirmados solo para administradores, recalcular precios/snapshots y preservar pagos; no permitir un nuevo total inferior al importe cobrado.
- [x] Recalcular deltas `SALE`/`SALE_CANCELLATION` y conciliar ledger/saldo de cuenta corriente dentro de la transacción.
- [ ] Flujos multi-depósito, si salen del alcance actual.

### Pagos y cuenta corriente

- [x] Aplicar pagos a deuda específica o mediante FIFO, con autorización seller/admin, locks, ledger append-only y auditoría.
- [x] Configurar límite de crédito global en DB desde endpoint administrativo.
- [x] Mostrar y auditar advertencias de saldo proyectado por encima del límite sin bloquear pedidos.
- [x] Registrar cobros durante la entrega y enviar el saldo restante a la cuenta corriente; ver `docs/api/delivery-collection.md`.

### Documentos y notificaciones

- [x] Ticket PDF de 80 mm bajo demanda y protegido por ownership.
- [x] Outbox transaccional para `ORDER_CONFIRMED`, clave idempotente, payload JSONB y estado persistente; V19.
- [x] Worker con lotes concurrentes, leases, backoff, límite de intentos y despacho al menos una vez; consumidores deduplican por evento.
- [x] Solicitudes idempotentes para email y WhatsApp mediante webhook configurable.
- [x] Auditoría de solicitudes, cada intento, éxitos, fallos y agotamiento de reintentos; consulta de estado protegida.

### Operación y entrega

- [~] Primera ejecución del workflow CI PostgreSQL en remoto.
- [ ] ArchUnit o Spring Modulith para validar límites modulares.
- [ ] Activar la tarea diaria de backup en el host operativo.
- [ ] Configurar y verificar copia externa de los backups.
- [ ] Aumentar cobertura HTTP/controller y completar administración de roles/permisos.

## Próxima prioridad sugerida

1. [x] Cobros durante la entrega.
2. [x] Imputación FIFO/específica de pagos.
3. [x] Límite de crédito global y advertencias auditadas.
4. [x] Outbox transaccional y worker con reintentos.
5. [x] Tickets y notificaciones configurables con auditoría.
6. [x] Endurecimiento operativo implementado; pendiente activación del CI remoto y operación diaria/externa de backups.
