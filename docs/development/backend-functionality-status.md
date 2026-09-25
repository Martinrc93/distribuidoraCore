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
- [x] Historial y vigencias actuales/futuras por fecha comercial; consulta y
  cancelación de programaciones. Contrato en `docs/api/pricing.md`.
- [x] La resolución comercial usa el historial efectivo en fecha de Buenos
  Aires; V21 agrega la línea base sin alterar snapshots existentes.
- [x] Reglas persistidas `LINE`/`ORDER`, vigencias, prioridad y scopes por
  cliente/lista/producto. La confirmación y edición usan reglas vigentes; los
  porcentajes e IDs aplicados quedan en snapshots de pedido y venta.
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
- [x] Mantener un saldo único por producto y movimientos de stock sin ubicación.
- [x] V24 consolidar los saldos V23 sumando cantidades por producto; conservar movimientos/pedidos/ventas y eliminar su atribución histórica a depósitos.
- [x] Confirmar, editar, devolver y cancelar pedidos directamente contra el saldo único.
- [x] Consultar y ajustar el stock único; ya no existen endpoints de depósitos ni transferencias. Contrato en `docs/api/inventory.md`.

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
- [x] PostgreSQL 16.4 real: Flyway V1–V24, validación JPA y pruebas funcionales opt-in de sesiones, catálogo, precios/descuentos, inventario único, ventas, devoluciones, edición/cancelación, cobros, imputación FIFO/específica, límites de crédito, outbox y notificaciones.
- [x] Verificación PostgreSQL opt-in previa (2026-09-24): 314 tests, 0 fallos, 0 errores ni omitidos; PostgreSQL 16.4, Flyway V1–V20 y 19 casos funcionales de integración.
- [x] Verificación posterior a los cambios de arquitectura, HTTP y revisión global del grafo (2026-09-24): suite Maven con 329 tests, 0 fallos, 0 errores y 19 omitidos porque `POSTGRES_TEST_URL` no estaba configurado en esa ejecución; las reglas ArchUnit, incluida la global de ciclos, pasaron.
- [x] Matriz enfocada de cadena de seguridad de producción: 6 casos pasan con `SecurityConfig`, filtro JWT y tokens firmados; cubre `401`, `403` y permisos `ADMIN_ALL`, `USER_MANAGE`, `ORDER_CREATE`, `SALE_PAYMENT`, `SALE_DELIVER` y `STOCK_ADJUST` en rutas críticas.
- [x] Verificación completa PostgreSQL (2026-09-24): 337 tests, 0 fallos, 0 errores ni omitidos; 21 casos de integración pasaron en PostgreSQL 16.4 con Flyway V1–V20 y `ddl-auto=validate` sobre un cluster descartable, incluyendo login HTTP y revocación de JWT tras cambios de rol/permisos.
- [x] Verificación incremental de precios (2026-09-24): 31 tests dirigidos y 22 casos PostgreSQL pasaron; Flyway V1–V21 y validación JPA en PostgreSQL 16.4 descartable.
- [x] Verificación incremental de descuentos (2026-09-24): 31 tests dirigidos y 23 casos PostgreSQL pasaron; Flyway V1–V22 y validación JPA en PostgreSQL 16.4 descartable.
- [x] Verificación histórica de multi-depósito (2026-09-24, antes de V24): 56 tests dirigidos y 24 casos PostgreSQL pasaron; esa funcionalidad fue retirada posteriormente por la regla de stock único.
- [x] Suite completa tras los tres ítems de backlog (2026-09-24, antes de V24): **350 tests, 0 fallos, 0 errores y 0 omitidos**; 24 casos PostgreSQL 16.4, Flyway V1–V23, validación JPA y ArchUnit global pasaron en esa verificación histórica.

## Parcial o requiere endurecimiento

- [x] La matriz HTTP cubre las seis authorities funcionales actuales en rutas críticas; los flujos PostgreSQL de login/cambio de rol y permisos prueban la invalidación inmediata del JWT anterior y las authorities efectivas del nuevo login.
- [x] Administración backend de usuarios/roles/permisos: consulta paginada, cambio de rol, catálogo de permisos y reemplazo de permisos por rol. Se protege al último ADMIN activo, se revocan sesiones y se auditan cambios. Contrato en `docs/api/identity-admin.md`.
- [x] Workflow CI PostgreSQL 16 ejecutado correctamente en GitHub Actions para `2d1decf` ([run 35957213828](https://github.com/Martinrc93/distribuidoraCore/actions/runs/35957213828), 2026-09-24).
- [x] Logs ECS JSON con request ID en MDC, métricas HTTP de latencia e histogramas y métricas operativas del outbox.
- [x] Scripts de backup cifrado AES-256-CBC/HMAC-SHA256, retención local, restauración/tamper y escrow externo verificados. La tarea diaria quedó habilitada tras una corrida programada de control con código `0`; el backup nuevo pasó HMAC y restore en PostgreSQL descartable (Flyway V7). El usuario confirmó el backup de las 05:55:56 en OneDrive. KeePassXC 2.7.12 guarda la clave DPAPI en la bóveda real; el usuario confirmó `RESULT=OK`, la lectura de vuelta pasó y Cloud Files devolvió `0x00000009` (`PLACEHOLDER` + `InSync`) para esa bóveda.
- [x] Procedimiento de rollback de aplicación y recuperación de PostgreSQL documentado.
- [x] Cuatro reglas ArchUnit protegen API→infraestructura, dominio→capas de entrega, `shared`↛`catalog` y `application`↛`api`; la quinta regla exige slices de producción sin ciclos.
- [x] Se eliminó la arista `shared → catalog` que cerraba el ciclo conocido `audit → shared → catalog → audit`, y las dependencias `application → DTOs API`.
- [x] Se revisó y documentó el grafo completo. Los ciclos con identidad, documentos y pedidos se resolvieron moviendo servicios de seguridad y handlers de errores al módulo propietario; la regla ArchUnit global pasa.
- [x] Purga por defecto a 90 días de solicitudes terminales y eventos outbox; auditoría enmascarada se conserva.

## Complementos incorporados después de la línea base

Los puntos de esta sección se añadieron al documento después de la primera
implementación; todos están completados y verificados. La configuración de
proveedores externos por entorno se mantiene como requisito de despliegue en el
roadmap, no como funcionalidad backend pendiente.

### Identidad y sesiones

- [x] Alta administrativa con estado `INVITED` y token de activación de un solo uso (30 minutos).
- [x] Endpoints dedicados `GET /api/roles`, `GET /api/permissions` y `PUT /api/roles/{roleCode}/permissions`; `ADMIN_ALL`/`USER_MANAGE` protegidos contra asignación a otros roles.

### Vendedores

- [x] CRUD administrativo de perfiles seller y asociación con usuarios.

### Catálogo y pricing

- [x] Historial de precios y vigencias futuras implementados y verificados en PostgreSQL; contrato en `docs/api/pricing.md`.
- [x] Reglas de descuentos comerciales persistidas, aplicadas en confirmación/edición y visibles en snapshots; contrato en `docs/api/pricing.md`.

### Inventario y modificaciones comerciales

- [x] Editar pedidos confirmados solo para administradores, recalcular precios/snapshots y preservar pagos; no permitir un nuevo total inferior al importe cobrado.
- [x] Recalcular deltas `SALE`/`SALE_CANCELLATION` y conciliar ledger/saldo de cuenta corriente dentro de la transacción.
- [x] Inventario de depósito único implementado: V24 suma balances existentes por producto y pedidos/ventas modifican directamente el stock único. Ver `docs/api/inventory.md`.

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

- [x] ArchUnit valida cuatro límites de capas y que los slices de producción estén libres de ciclos.
- [x] La arista que cerraba `audit → shared → catalog → audit` y las dependencias aplicación→DTOs API se eliminaron.
- [x] El grafo intermodular completo está documentado en `docs/architecture/module-boundaries.md`; identidad, documentos y pedidos ya no generan ciclos desde `shared`.
- [x] Corregir Task Scheduler y activar la tarea diaria; ejecución programada de control: resultado `0`. El canal Operational sigue deshabilitado y Windows denegó su activación/consulta; se diagnosticó mediante tareas temporales y log por ejecución.
- [x] Completar protección externa: backup en OneDrive confirmado por el usuario, restauración/HMAC probadas y clave DPAPI guardada y leída desde KeePassXC 2.7.12; Cloud Files confirmó la bóveda sincronizada (`0x00000009`, `PLACEHOLDER` + `InSync`). La contraseña maestra queda bajo custodia del usuario fuera de OneDrive.
- [x] Cobertura HTTP representativa de controllers y matriz de seguridad para las seis authorities actuales en rutas críticas, más login/cambio de permisos con PostgreSQL real. La matriz no pretende probar exhaustivamente cada método de cada controller.

## Próxima prioridad sugerida

1. [x] Cobros durante la entrega.
2. [x] Imputación FIFO/específica de pagos.
3. [x] Límite de crédito global y advertencias auditadas.
4. [x] Outbox transaccional y worker con reintentos.
5. [x] Tickets y notificaciones configurables con auditoría.
6. [x] Endurecimiento operativo: CI, backup/restore, tarea diaria, presencia del backup en OneDrive y escrow KeePassXC sincronizado están verificados.
