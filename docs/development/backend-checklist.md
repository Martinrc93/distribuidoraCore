# Backend Development Checklist

Estado actualizado: 2026-09-19

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
- [x] Refresh tokens rotativos, revocables y almacenados como hash.
- [x] Bloqueo/desbloqueo administrativo y revocación de sesiones.
- [ ] Endpoints administrativos completos para usuarios, roles y permisos.

## Customers, Sellers Y Catalog

- [x] CRUD de clientes sobre PostgreSQL.
- [x] Baja lógica de clientes.
- [x] CRUD de productos y baja lógica.
- [x] Validación de unicidad, importes y estados.
- [~] Perfiles de vendedor creados en seed, pero falta CRUD administrativo completo.
- [ ] CRUD de vendedores y asociación con usuarios.
- [ ] Restricción de vendedores a clientes asignados.
- [ ] APIs internas para resolver vendedor asignado.
- [ ] Catálogo completo de marcas y categorías como entidades administrables.

## Pricing

- [x] Migración V5 con `price_lists` y `product_prices`.
- [x] Listas `GENERAL`, `LISTA_2` y `LISTA_3`.
- [x] Máximo de diez listas con protección concurrente.
- [x] Precios `NUMERIC(19,4)` y validación de precisión.
- [x] Asignación opcional de lista a clientes.
- [x] Resolución explícita, asignada y fallback `GENERAL`.
- [x] Auditoría y autorización `ADMIN_ALL`.
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
- [ ] Reversión `SALE_CANCELLATION` al cancelar una venta.
- [ ] Deltas compensatorios al editar un pedido confirmado.
- [ ] Operaciones de devolución `RETURN`.
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
- [ ] Marcar entrega `DELIVERED`.
- [ ] Registrar intentos fallidos de entrega.
- [ ] Cancelar `CONFIRMED` sin pagos y revertir stock.
- [ ] Impedir cancelación de ventas pagadas.
- [ ] Editar pedidos confirmados solo para administradores.
- [ ] Recalcular deltas de stock al editar.
- [ ] Aplicar pagos a deuda específica o FIFO.
- [ ] Configurar límite de crédito global y advertencias auditadas.

## Documents, Notifications Y Outbox

- [ ] Crear outbox transaccional para eventos comerciales.
- [ ] Worker de outbox con reintentos e idempotencia.
- [x] Generar PDF bajo demanda para documentos A4.
- [x] Descargar e imprimir documentos A4.
- [ ] Descargar e imprimir tickets.
- [ ] Link/proveedor configurable de WhatsApp.
- [ ] Auditoría de solicitudes y reintentos de notificación.

## Operación Y Calidad

- [x] Tests unitarios de aplicación y smoke test PostgreSQL.
- [x] Verificación de migraciones V1-V6 en PostgreSQL descartable.
- [~] Cobertura HTTP/controller todavía menor que la cobertura de servicios.
- [ ] Tests de integración automatizados permanentes en CI.
- [ ] ArchUnit o Spring Modulith para validar límites modulares.
- [ ] Logs estructurados y métricas de latencia/errores.
- [ ] Backups diarios con retención definida.
- [ ] Prueba automatizada de restauración.
- [ ] Procedimiento operativo de rollback y recuperación.

## Siguiente Orden Recomendado

1. Entrega, cancelación y reversión de stock.
2. Edición administrativa de pedidos confirmados.
3. FIFO de cuenta corriente y límite de crédito.
4. Outbox, documentos y notificaciones.
5. Hardening operativo, integración CI y backups.

## Criterio De Cierre Backend

Una funcionalidad se marca `[x]` solo cuando tiene command/query implementado, autorización backend, auditoría cuando corresponde, tests automatizados, endpoint documentado y verificación contra PostgreSQL si modifica transacciones comerciales.
