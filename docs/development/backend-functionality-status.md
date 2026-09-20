# Estado De Funcionalidades Del Backend

Estado relevado: 2026-09-20.

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

### Clientes

- [x] Crear clientes.
- [x] Editar clientes.
- [x] Activar y desactivar clientes lógicamente.
- [x] Asignar vendedor.
- [x] Asignar y limpiar lista de precios.
- [x] Validar identificación fiscal única.
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
- [x] Smoke tests PostgreSQL para flujos comerciales y lifecycle.
- [x] Suite backend actual verificada con 127 tests exitosos.

## Parcial o requiere endurecimiento

- [~] La cobertura HTTP es menor que la cobertura de servicios en algunos módulos.
- [~] El endpoint de usuarios existe, pero todavía no ofrece administración completa de roles/permisos y estados.
- [~] Los perfiles seller pueden crearse durante el alta de usuarios, pero no existe CRUD administrativo completo de vendedores.
- [~] Los catálogos de marcas y categorías se manejan como datos de producto, no como entidades administrables completas.
- [~] La autorización depende de permisos persistidos y JWT, pero todavía falta completar refresh/revocación de sesiones.
- [~] Los tests de integración PostgreSQL existen para contratos y smoke, pero falta ejecución permanente en CI.
- [~] La observabilidad tiene health checks y request IDs, pero faltan métricas de latencia/errores y logs estructurados completos.

## Pendiente

### Identidad y sesiones

- [ ] Alta administrativa con estado `INVITED` y flujo completo de invitación.
- [ ] Token de activación de un solo uso con expiración de 30 minutos.
- [ ] Refresh tokens rotativos, revocables y almacenados como hash.
- [ ] Bloqueo y desbloqueo administrativo.
- [ ] Revocación de sesiones y tokens activos.
- [ ] Administración completa de roles y permisos desde endpoints dedicados.

### Vendedores

- [ ] CRUD completo de perfiles seller.
- [ ] Asociar y desasociar vendedores de usuarios existentes.
- [ ] Activar y desactivar vendedores desde administración.
- [ ] Reglas de reasignación masiva de clientes y pedidos.

### Catálogo y pricing

- [ ] Historial de precios.
- [ ] Vigencias futuras de precios.
- [ ] Reglas de descuentos comerciales persistidas fuera de la confirmación.
- [ ] CRUD administrativo de marcas y categorías.

### Inventario y modificaciones comerciales

- [ ] Editar pedidos confirmados solo para administradores.
- [ ] Recalcular deltas de stock al editar pedidos confirmados.
- [ ] Operaciones de devolución `RETURN`.
- [ ] Flujos multi-depósito, si salen del alcance actual.

### Pagos y cuenta corriente

- [ ] Aplicar pagos a deuda específica o mediante FIFO.
- [ ] Configurar límite de crédito global.
- [ ] Advertencias auditadas por exceso de crédito.

### Documentos y notificaciones

- [ ] Tickets y otros formatos de comprobante.
- [ ] Outbox transaccional para eventos comerciales.
- [ ] Worker de outbox con reintentos e idempotencia.
- [ ] Envío por WhatsApp mediante link o proveedor configurable.
- [ ] Envío por email.
- [ ] Auditoría de solicitudes y reintentos de notificación.

### Operación y entrega

- [ ] Tests de integración permanentes en CI.
- [ ] ArchUnit o Spring Modulith para validar límites modulares.
- [ ] Logs estructurados y métricas de latencia, errores y disponibilidad.
- [ ] Backups diarios de PostgreSQL con retención definida.
- [ ] Prueba automatizada de restauración.
- [ ] Procedimiento documentado de rollback y recuperación.

## Próxima prioridad sugerida

1. Completar identidad: invitaciones, refresh tokens, bloqueo y revocación.
2. Completar administración de vendedores y roles/permisos.
3. Implementar edición administrativa de pedidos confirmados y devoluciones.
4. Implementar FIFO y límite de crédito.
5. Implementar outbox, notificaciones y tickets.
6. Completar hardening operativo, CI y backups.
