# Backend Development Checklist

Estado actualizado: 2026-09-25

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
- [x] APIs de administración para listar usuarios/roles/permisos, cambiar rol y reemplazar permisos de rol, con restricciones de elevación, invalidación de sesiones y auditoría. Contrato: [`identity-admin.md`](../api/identity-admin.md).

## Customers, Sellers Y Catalog

- [x] CRUD de clientes sobre PostgreSQL.
- [x] Baja lógica de clientes.
- [x] CRUD de productos y baja lógica.
- [x] Alta de producto exige al menos un precio válido para una lista activa; payload omitido/vacío se rechaza antes de insertar.
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
- [x] Configurar descuentos manuales o reglas comerciales requiere `ADMIN_ALL`; los overrides manuales siguen protegidos.
- [x] Historial de precios y vigencias futuras: V21, consulta/cancelación de programaciones, resolución por fecha comercial de Buenos Aires, límite costo-precio y snapshots históricos intactos. Verificación específica: 31 tests dirigidos y 22 casos PostgreSQL.
- [x] Reglas de descuentos comerciales persistidas: scopes `LINE`/`ORDER`, prioridad, vigencias, selección automática y snapshots con regla/porcentaje. API en `docs/api/pricing.md`; 31 tests dirigidos y 23 casos PostgreSQL.

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
- [x] Depósito `CENTRAL` predeterminado; V23 migra balances/movimientos y asigna operaciones existentes sin perder stock.
- [x] CRUD administrativo y consulta paginada de balances por depósito; el predeterminado no se desactiva.
- [x] Ajustes opcionales por depósito y transferencias atómicas, con lock determinista, saldo suficiente en origen, dos movimientos y auditoría.
- [x] Confirmar pedidos en un depósito seleccionado; edición, devolución y cancelación conservan/restauran el depósito original.
- [x] `/api/inventory` suma stock de todos los depósitos y los movimientos exponen su depósito; contrato en `docs/api/inventory.md`.
- [x] Diagramas de flujo y ER actualizados en `docs/diagrams/inventory/`.

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
- [ ] Para activar envíos reales, configurar la URL y las credenciales del proveedor en cada entorno; la integración y su manejo de reintentos ya están implementados.

## Operación Y Calidad

- [x] Verificación PostgreSQL opt-in previa (2026-09-24): 314 tests, 0 fallos, 0 errores ni omitidos; 19 casos funcionales contra PostgreSQL 16.4, migraciones V1–V20 y validación JPA.
- [x] Verificación posterior a arquitectura, HTTP y grafo modular (2026-09-24): 329 tests, 0 fallos, 0 errores; 19 omitidos porque `POSTGRES_TEST_URL` no estaba configurado en esta ejecución. ArchUnit pasó, incluida la regla global sin ciclos.
- [x] Verificación final con matriz HTTP y PostgreSQL descartable (2026-09-24): 337 tests, 0 fallos, 0 errores y 0 omitidos; los 21 casos PostgreSQL pasaron en PostgreSQL 16.4/Flyway V1–V20, incluyendo login HTTP y revocación de JWT por cambio de rol/permisos.
- [x] Historial/vigencias de precios (2026-09-24): 31 tests dirigidos de pricing/productos pasaron; 22 casos de integración pasaron en PostgreSQL 16.4, Flyway V1–V21 y `ddl-auto=validate` sobre instancia descartable. PostgreSQL encontró un alias inválido en la primera ejecución; se corrigió y la suite completa pasó en la repetición.
- [x] Reglas de descuentos (2026-09-24): 31 tests dirigidos pasaron; 23 casos de integración pasaron en PostgreSQL 16.4, Flyway V1–V22 y `ddl-auto=validate` sobre base nueva del cluster TEMP verificado.
- [x] Multi-depósito (2026-09-24): 56 tests dirigidos pasaron, incluidos seis casos de autorización HTTP; 24 casos de integración pasaron en PostgreSQL 16.4, Flyway V1–V23 y `ddl-auto=validate` en una base nueva del cluster TEMP verificado.
- [x] Suite completa de cierre del backlog (2026-09-24): **350 tests, 0 fallos, 0 errores y 0 omitidos**, incluidos 24 casos PostgreSQL 16.4/Flyway V1–V23 y validación JPA en una base descartable nueva; ArchUnit global pasó.
- [x] Verificación de migraciones V1–V20 en PostgreSQL descartable.
- [x] Todos los tests `*ControllerTest` ejercitan rutas mediante `MockMvc`; se agregaron 14 casos para marcas, categorías, pagos, entregas, devoluciones, vendedores y límite de crédito.
- [x] Matriz de cadena real: seis casos para las authorities actuales (`ADMIN_ALL`, `USER_MANAGE`, `ORDER_CREATE`, `SALE_PAYMENT`, `SALE_DELIVER`, `STOCK_ADJUST`) y límites entre rutas críticas.
- [x] Workflow CI de PostgreSQL 16 ejecutado en GitHub Actions; [run 35957213828](https://github.com/Martinrc93/distribuidoraCore/actions/runs/35957213828) terminó correctamente para commit `2d1decf` (2026-09-24).
- [x] CI PostgreSQL: el [run 36073594320](https://github.com/Martinrc93/distribuidoraCore/actions/runs/36073594320), sobre `0bf06c6`, registró `ConflictingBeanDefinitionException` para los beans `jwtAuthenticationFilter` de `shared.security` e `identity.security`. El job corría `mvn test` sin limpiar clases compiladas obsoletas; el workflow ahora escucha `main` en push/PR y ejecuta `mvn clean test`. El [run 36103255055](https://github.com/Martinrc93/distribuidoraCore/actions/runs/36103255055) pasó para PR #1 a `main`, HEAD `9753eafe19fa5580164db6365bb112327d232898` (job PostgreSQL, 1m14s).
- [x] Validar que el alta de producto exige al menos un precio en una lista activa, según `docs/domain/functionalities.md`; el alta ausente/vacía devuelve 400 y deja cero productos, el alta válida persiste su precio. Pruebas HTTP/PostgreSQL en `PostgresBackendFixesIntegrationTest`.
- [x] Flujo comercial PostgreSQL HTTP: login, permisos `403`, alta de cliente/producto, inventario, confirmación idempotente, warning de crédito, detalle, deuda específica y entrega/cobro; repetición devuelve los mismos IDs, saldo `40`, stock `8`.
- [x] Verificación de `f92c40841b8b61c1c02dbb1e32991c2883666410`: PostgreSQL 16.4, Flyway V1–V23, **355 tests; 0 fallos/errores/omitidos; 26 casos PostgreSQL**.
- [x] ArchUnit protege cuatro límites de capas y aplica una quinta regla (`ModuleBoundaryTest`) para mantener acíclicos los slices de producción.
- [x] Se eliminó `shared → catalog`, que cerraba el ciclo identificado `audit → shared → catalog → audit`, y se desacoplaron los servicios de aplicación de DTOs API.
- [x] Revisar/documentar el grafo completo y resolver los ciclos que involucraban identidad, documentos y pedidos; `shared` quedó sin dependencias salientes hacia módulos de negocio.
- [x] Logs ECS JSON, request ID en MDC, métricas HTTP y métricas del outbox.
- [x] Backup cifrado/restauración y escrow externo verificados; la tarea diaria quedó activa tras una ejecución programada de control con resultado `0`. El usuario confirmó el backup de las 05:55:56 en OneDrive. KeePassXC 2.7.12 guarda la clave DPAPI en una bóveda real; el usuario confirmó `RESULT=OK`, la lectura de vuelta pasó y Cloud Files `0x00000009` confirma `PLACEHOLDER` + `InSync`.
- [x] Restauración automatizada verificada en base PostgreSQL descartable, incluido rechazo de backup alterado.
- [x] Procedimiento documentado de rollback de aplicación y recuperación PostgreSQL.
- [x] Purga a 90 días de destinatarios y eventos outbox terminales; auditoría enmascarada se conserva.

## Siguiente Orden Recomendado

1. [x] Cobros durante la entrega: medio de pago, referencia opcional de transferencia y saldo restante a cuenta corriente.
2. [x] Imputar pagos a una deuda específica o por FIFO.
3. [x] Configurar el límite de crédito global y advertencias auditadas por exceso.
4. [x] Implementar outbox transaccional y worker con reintentos e idempotencia.
5. [x] Agregar tickets y flujos configurables de WhatsApp/email con auditoría de solicitudes y reintentos.
6. [x] Implementar endurecimiento operativo: workflow CI PostgreSQL, métricas/logs, scripts cifrados de backup/restauración, rollback, purga de retención y escrow KeePassXC sincronizado a OneDrive.

Verificación al cierre de la tarea 6: suite completa **301 tests, 0 fallos, 0
errores y 0 omitidos**, con PostgreSQL 16.4 y Flyway V1–V20; 18 casos
funcionales de integración. La prueba de backup/restauración y rechazo de
alteración HMAC terminó en una base descartable y la eliminó.

Verificación PostgreSQL previa (2026-09-24): **314 tests, 0 fallos, 0 errores ni
omitidos**, incluidos 19 casos PostgreSQL en una base descartable Flyway V1–V20;
la base se eliminó al terminar. Verificación anterior a la revisión global del
grafo (2026-09-24): **328 tests, 0 fallos, 0 errores y 19 omitidos** por falta
de `POSTGRES_TEST_URL`. Verificación actual tras revisar el grafo (2026-09-24):
**329 tests, 0 fallos, 0 errores y 19 omitidos**; ArchUnit pasó y esta ejecución
no tenía `POSTGRES_TEST_URL`.

## Próximas tareas

1. [x] Diagnosticar y activar la tarea diaria: se corrigió la invocación de PowerShell y el log por ejecución, se conservó la clave DPAPI existente y se verificó una corrida con resultado `0`. Próxima ejecución: 2026-09-25 03:00.
2. [x] Completar protección externa:
   - [x] Confirmar el archivo `distribuidora-20260924-055556.dcbak` en OneDrive: confirmado por el usuario; localmente pasó HMAC. Cloud Files `0x00000009` es placeholder + `InSync`.
   - [x] Guardar y verificar el escrow de la clave DPAPI en KeePassXC 2.7.12 portable. El usuario confirmó `RESULT=OK`; la entrada se leyó de vuelta y Cloud Files reportó `0x00000009` (`PLACEHOLDER` + `InSync`) para la bóveda en OneDrive. La contraseña maestra queda bajo custodia del usuario, fuera de OneDrive.
3. [x] Incorporar ArchUnit para proteger límites de capas e identificar la deuda intermodular.
4. [x] Reducir la deuda arquitectónica identificada.
   - [x] Romper `audit → shared → catalog → audit` eliminando `shared → catalog` y proteger esa dirección con ArchUnit.
   - [x] Mover los DTOs de transporte fuera de dependencias de aplicación y proteger `application`↛`api`.
   - [x] Revisar y documentar el grafo intermodular completo; activar la regla global ArchUnit después de resolver los ciclos.
5. [x] Ampliar cobertura HTTP/controller.
   - [x] Añadir 14 casos `MockMvc` representativos a siete controladores que solo tenían pruebas de invocación directa.
- [x] Matriz con filtros de producción y flujos PostgreSQL reales: authorities críticas cubiertas; el login lee permisos persistidos y los cambios de rol/permisos invalidan el JWT previo.
6. [x] Revisar el grafo completo de dependencias entre módulos.
   - [x] Identificar y documentar los ciclos encontrados y los paquetes aislados (`demo`, `payment`) en `docs/architecture/module-boundaries.md`.
   - [x] Resolver las dependencias de identidad, documentos y pedidos; habilitar y pasar la regla global ArchUnit.
7. [x] Verificar autorización HTTP y persistencia con entornos reales.
   - [x] Con la `SecurityConfig` de producción, comprobar acceso anónimo (`401`), autoridad insuficiente (`403`), autorización `ADMIN_ALL` y acceso seller `ORDER_CREATE` mediante JWT firmado.
   - [x] Repetir la suite con PostgreSQL 16.4 descartable; Flyway V1–V20, validación JPA y 19 casos de integración pasaron sin omisiones.
8. [x] Ampliar la matriz de seguridad a las rutas críticas restantes.
   - [x] Cubrir las seis authorities funcionales actuales con la cadena de filtros de producción y verificar `401`/`403` cuando corresponda.
   - [x] Probar sobre PostgreSQL login con rol/permisos persistidos, cambio HTTP de rol y permisos, `401` del JWT anterior y permisos efectivos del nuevo login.

Estado del backlog autorizado (2026-09-24): historial/vigencias de precios,
reglas de descuentos persistidas y multi-depósito completados, probados y
documentados. Los tres ítems de este lote están cerrados. La verificación
completa final pasó con 350 tests. Los tres ítems de implementación de este
lote quedaron cerrados. La configuración operativa de URLs y credenciales
reales del proveedor de notificaciones sigue pendiente; no se habilitaron
envíos externos sin esos datos. La verificación del 2026-09-25 agregó dos
pendientes nuevos: recuperar el CI de la punta integrada y exigir precio por
lista activa al crear productos. Ambos pendientes quedaron cerrados en la rama
`codex/integration-followup-docs`; ver evidencia del ciclo integrado arriba.

## Criterio De Cierre Backend

Una funcionalidad se marca `[x]` solo cuando tiene command/query implementado, autorización backend, auditoría cuando corresponde, tests automatizados, endpoint documentado y verificación contra PostgreSQL si modifica transacciones comerciales.
