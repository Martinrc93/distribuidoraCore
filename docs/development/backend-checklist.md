# Backend Development Checklist

Estado actualizado: 2026-09-24

## Cómo Leerlo

- `[x]` Implementado y verificado.
- `[ ]` Pendiente.
- `[~]` Parcial o requiere endurecimiento.
- Cada ítem debe cerrar con tests, documentación HTTP y verificación PostgreSQL cuando corresponda.

## Plataforma Y Seguridad

- [x] Monolito modular Spring Boot con Java 21.
- [x] PostgreSQL 16, Flyway y schemas por módulo.
- [x] Docker Compose para PostgreSQL, backend y frontend.
- [x] Actuator health/readiness y `X-Request-Id`.
- [x] Argon2, JWT stateless y bloqueo tras intentos fallidos.
- [x] Autoridades JWT cargadas desde roles/permisos persistidos.
- [x] Auditoría append-only con actor, operación y request ID.
- [x] Alta administrativa de usuarios `INVITED`.
- [x] Activación con token de un solo uso y expiración.
- [x] Refresh tokens rotativos con hash, bloqueo por token y detección de reuso (suite Maven y pruebas funcionales PostgreSQL 16.4 pasan).
- [x] Bloqueo/desbloqueo administrativo y revocación de sesiones, incluyendo invalidación inmediata del access JWT mediante versión de sesión (suite Maven y pruebas funcionales PostgreSQL 16.4 pasan).
- [ ] Endpoints administrativos completos para usuarios, roles y permisos.

## Customers, Sellers Y Catalog

- [x] CRUD de clientes sobre PostgreSQL.
- [x] Baja lógica de clientes.
- [x] CRUD de productos y baja lógica.
- [x] Validación de unicidad, importes y estados.
- [x] CRUD administrativo completo de perfiles de vendedor.
- [x] CRUD de vendedores y asociación con usuarios.
- [x] Restricción de vendedores a clientes asignados.
- [x] APIs internas para resolver vendedor asignado.
- [x] Reasignación masiva de clientes y pedidos `CONFIRMED`; los estados históricos quedan protegidos (suite Maven y prueba funcional PostgreSQL pasan).
- [x] CRUD administrativo de marcas y categorías con referencias opcionales desde productos (suite Maven y prueba funcional PostgreSQL pasan).

## Pricing

- [x] Migración V5 con `price_lists` y `product_prices`.
- [x] Listas `GENERAL`, `LISTA_2` y `LISTA_3`.
- [x] Máximo de diez listas con protección concurrente.
- [x] Precios `NUMERIC(19,4)` y validación de precisión.
- [x] Asignación opcional de lista a clientes.
- [x] Resolución explícita, asignada y fallback `GENERAL`.
- [x] Auditoría y autorización `ADMIN_ALL`.
- [ ] Autorizar descuentos por producto y generales únicamente para `ADMIN`.
- [ ] Historial de precios y vigencias futuras, si el negocio lo requiere.
- [ ] Reglas de descuentos comerciales persistidas fuera de la confirmación.

## Inventory

- [x] Saldos actuales por producto.
- [x] Movimientos append-only.
- [x] Ajustes manuales con delta firmado.
- [x] Cantidades en múltiplos de `0.5` y saldos negativos permitidos.
- [x] Lock pesimista con `SELECT ... FOR UPDATE`.
- [x] Movimientos `SALE` para confirmación de pedidos.
- [x] Auditoría de ajustes y referencias de movimientos.
- [x] Reversión `SALE_CANCELLATION` al cancelar una venta confirmada sin pagos.
- [x] Edición administrativa de pedidos confirmados con deltas `SALE`/`SALE_CANCELLATION`; pagos existentes se conservan y el ledger compensa la deuda. La cancelación revierte el efecto neto de ambos tipos de movimiento.
- [x] Devoluciones `RETURN` parciales por línea de venta; límite acumulado bajo lock, movimiento de stock y auditoría transaccionales. Endpoint documentado en `docs/api/sale-returns.md`.
- [ ] Multi-depósito, fuera del alcance actual.

## Order, Sale, Payment Y Cuenta Corriente

- [x] Confirmación atómica `POST /api/orders/confirm`.
- [x] No se persisten borradores.
- [x] Snapshots de producto, lista, precio y descuentos.
- [x] Descuentos de línea y descuento total con `BigDecimal`.
- [x] Pagos `CASH` y `BANK_TRANSFER`.
- [x] `CUSTOMER_ACCOUNT` con ledger append-only.
- [x] Pagos parciales y combinados con saldo reconciliado.
- [x] Idempotencia por clave, fingerprint y advisory lock PostgreSQL.
- [x] Reintentos concurrentes sin duplicar efectos secundarios.
- [x] Lectura de detalle de pedido por ID y número.
- [x] Rollback real verificado después de efectos parciales.
- [x] Marcar entrega `DELIVERED`.
- [x] Registrar intentos fallidos de entrega.
- [x] Registrar cobros durante la entrega, referencia opcional de transferencia y saldo restante en cuenta corriente; ver `docs/api/delivery-collection.md`.
- [x] Cancelar `CONFIRMED` sin pagos y revertir stock.
- [x] Impedir cancelación de ventas pagadas.
- [x] Editar el contenido completo de pedidos `CONFIRMED` solo con `ADMIN_ALL`, conservando pagos y evitando reducir el total por debajo del importe ya pagado.
- [x] Recalcular deltas de stock y ajustar deuda de cuenta corriente de forma transaccional y auditada al editar; documentado en `docs/api/order-edits.md`.
- [x] Aplicar pagos a deuda específica o FIFO con lock de ventas/cliente, pagos y créditos por asignación, auditoría y contrato en `docs/api/account-payments.md`.
- [x] Límite de crédito global configurable y opcional; confirmar pedidos por encima del límite con advertencia de respuesta y auditoría, sin bloquear.

## Documents, Notifications Y Outbox

- [x] Crear outbox transaccional para `ORDER_CONFIRMED` con clave única.
- [x] Worker por lotes con `SKIP LOCKED`, lease, backoff, ocho intentos y estado de reintento agotado.
- [x] Generar PDF bajo demanda para documentos A4.
- [x] Descargar e imprimir documentos A4.
- [x] Descargar e imprimir tickets PDF de 80 mm.
- [x] Webhooks configurables de WhatsApp y email con timeout, token opcional e idempotency key.
- [x] Auditar solicitudes, intentos, éxitos, fallos y agotamiento de reintentos; consultar estado por solicitud.

## Operación Y Calidad

- [x] Suite Maven con PostgreSQL opt-in: 301 tests, 0 fallos, 0 errores y 0 omitidos; 18 casos funcionales contra PostgreSQL 16.4, migraciones V1–V20 y validación JPA.
- [x] Verificación de migraciones V1–V20 en PostgreSQL descartable.
- [~] Cobertura HTTP/controller todavía menor que la cobertura de servicios.
- [~] Workflow CI configurado para PostgreSQL 16; falta confirmar su primera ejecución remota.
- [ ] ArchUnit o Spring Modulith para validar límites modulares.
- [x] Logs ECS JSON, request ID en MDC, métricas HTTP y métricas del outbox.
- [~] Scripts de backup cifrado, retención y registro de tarea diaria listos; falta activar la tarea en el host y configurar copia externa.
- [x] Restauración automatizada verificada en base PostgreSQL descartable, incluido rechazo de backup alterado.
- [x] Procedimiento documentado de rollback de aplicación y recuperación PostgreSQL.
- [x] Purga a 90 días de destinatarios y eventos outbox terminales; auditoría enmascarada se conserva.

## Siguiente Orden Recomendado

1. [x] Cobros durante la entrega: medio de pago, referencia opcional de transferencia y saldo restante a cuenta corriente.
2. [x] Imputar pagos a una deuda específica o por FIFO.
3. [x] Configurar el límite de crédito global y advertencias auditadas por exceso.
4. [x] Implementar outbox transaccional y worker con reintentos e idempotencia.
5. [x] Agregar tickets y flujos configurables de WhatsApp/email con auditoría de solicitudes y reintentos.
6. [x] Implementar endurecimiento operativo: workflow CI PostgreSQL, métricas/logs, scripts cifrados de backup/restauración, rollback y purga de retención. Quedan activación del workflow remoto, programación de la tarea en el host y réplica externa.

Verificación al cierre de la tarea 6: suite completa **301 tests, 0 fallos, 0
errores y 0 omitidos**, con PostgreSQL 16.4 y Flyway V1–V20; 18 casos
funcionales de integración. La prueba de backup/restauración y rechazo de
alteración HMAC terminó en una base descartable y la eliminó.

## Próximas tareas

1. Confirmar la primera ejecución del workflow en GitHub Actions.
2. Activar la tarea programada diaria de backup en el host operativo.
3. Configurar y probar copia externa de los backups cifrados.
4. Incorporar ArchUnit o Spring Modulith para validar límites modulares.
5. Aumentar cobertura HTTP/controller y completar administración de roles y permisos.

## Criterio De Cierre Backend

Una funcionalidad se marca `[x]` solo cuando tiene command/query implementado, autorización backend, auditoría cuando corresponde, tests automatizados, endpoint documentado y verificación contra PostgreSQL si modifica transacciones comerciales.
