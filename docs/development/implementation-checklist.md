# Checklist de implementacion

Estado de referencia: 2026-09-25.

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
- [x] Historial de precios y vigencias futuras con resolución por fecha comercial; migración V21.
- [x] Reglas persistidas de descuentos de línea y pedido con prioridad, vigencia y snapshots; migración V22.
- [x] Editar costo sin precio cuando no supera ninguna lista activa.
- [x] Exigir nuevos precios para todas las listas afectadas cuando el costo las supera.
- [x] Actualizar costo y precios afectados en una sola transaccion.
- [x] Exponer las listas afectadas en el error de validacion.
- [x] Eliminar historial de costos del alcance funcional.
- [x] CRUD administrativo de marcas y categorías y asociación opcional en productos (tests Maven y PostgreSQL funcionales pasan).
- [x] Restringir configuración y aplicación de descuentos por producto o generales a `ADMIN`.

### Inventario

- [x] Saldos actuales y movimientos append-only.
- [x] Ajustes manuales con delta firmado y lock pesimista.
- [x] Movimientos `SALE` al confirmar pedidos.
- [x] Reversión neta `SALE_CANCELLATION` al cancelar pedidos confirmados sin pagos, incluyendo movimientos compensatorios de ediciones anteriores.
- [x] Devoluciones `RETURN` parciales con saldo por línea, lock por venta y reintegro de stock transaccional; ver [contrato de API](../api/sale-returns.md).
- [x] Edición administrativa de pedido/venta `CONFIRMED` con reemplazo de snapshots, deltas compensatorios, pagos preservados y conciliación de ledger; ver [contrato de API](../api/order-edits.md).
- [x] Multi-depósito: administración, balances, ajustes y transferencias; pedido, devolución y cancelación conservan el depósito elegido. Contrato y diagramas en `docs/api/inventory.md` y `docs/diagrams/inventory/`.

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
- [ ] Configurar URL y credenciales de los proveedores email/WhatsApp en cada entorno antes de activar envíos reales.
- [x] Métricas HTTP/outbox, logs ECS JSON y propagación segura de request ID.
- [x] Backup cifrado, retención definida, restauración automatizada y procedimiento de rollback documentado.
- [x] Endurecimiento operativo verificado: CI PostgreSQL 16 pasó en GitHub Actions para `2d1decf` ([run 35957213828](https://github.com/Martinrc93/distribuidoraCore/actions/runs/35957213828), 2026-09-24). La tarea diaria y el control programado terminaron con código `0`; restore/tamper pasó en PostgreSQL descartable. El usuario confirmó el backup de las 05:55:56 en OneDrive y el estado Cloud Files `0x00000009` (`PLACEHOLDER` + `InSync`). KeePassXC 2.7.12 guarda la clave DPAPI; el usuario confirmó `RESULT=OK` y la lectura de vuelta pasó.
- [x] Purga de destinatarios de notificaciones y eventos terminales tras 90 días por defecto.
- [x] ArchUnit valida cuatro límites de capas más la ausencia de ciclos entre slices de producción.
- [x] Romper el ciclo identificado `audit → shared → catalog → audit` y desacoplar DTOs API de servicios de aplicación.
- [x] Revisar y documentar el grafo completo, resolver los ciclos encontrados y activar la regla ArchUnit global.
- [x] Ampliar cobertura HTTP con 14 casos `MockMvc` para siete controladores que solo tenían pruebas de invocación directa.
- [x] Matriz real de seis casos HTTP: cubre `401`/`403`, authorities funcionales y rutas críticas; PostgreSQL valida login persistido, revocación del JWT anterior y permisos del nuevo login después de cambiar rol o permisos.
- [x] Administración backend de usuarios/roles/permisos; ver `docs/api/identity-admin.md`.

## Frontend

### Base y seguridad

- [x] React, TypeScript, Vite, Router y TanStack Query.
- [x] Cliente HTTP autenticado y shell protegido.
- [x] Layout responsive desktop/mobile.
- [x] Estados de carga, vacio y error principales.
- [x] Manejo de sesión expirada con refresh rotativo/revocación.
- [x] Ocultar rutas/acciones según permisos actuales; backend autoriza los comandos.
- [~] Foco visible, labels y controles etiquetados en módulos nuevos; auditoría completa pendiente.

### Usuarios, vendedores y clientes

- [x] Listado, alta y edicion de clientes.
- [x] Asignacion de vendedor y lista de precios.
- [x] Baja logica y reactivacion.
- [x] Invitación/creación de usuarios, activación, bloqueo, desbloqueo y revocación de sesiones.
- [x] Mostrar roles devueltos por API en modo de solo lectura.
- [ ] Reasignación de roles y edición de permisos (API disponible; aplazado por decisión del usuario).
- [x] CRUD de vendedores y reasignación masiva de clientes/pedidos.
- [ ] Detalle de cliente con ventas, pagos y cuenta corriente (falta consulta dedicada por cliente).

### Catalogo y precios

- [x] Listado, alta, edicion y baja logica de productos.
- [x] Validacion local de datos basicos y mensajes HTTP.
- [x] Quitar el campo y la columna de precio general del producto.
- [x] Mostrar precios exclusivamente por lista.
- [x] Crear y editar precios por producto/lista.
- [x] Editar costo sin pedir precios cuando no hay listas afectadas.
- [x] Mostrar automáticamente listas afectadas cuando el nuevo costo las supera.
- [x] Enviar costo y precios afectados en la misma operación.
- [x] Mostrar errores de listas faltantes o precios menores al costo.
- [x] Pantalla de administración de listas, marcas y categorías.
- [x] Programar vigencias de precios; consultar historial paginado y cancelar precios futuros.
- [x] Administrar reglas de descuentos por línea/pedido con vigencia, alcance y prioridad (`ADMIN_ALL`).

### Inventario

- [x] Consulta de saldos.
- [x] Saldos y vista paginada de movimientos por producto.
- [x] Formulario de ajuste manual.
- [x] Permisos, confirmación y advertencia de saldo negativo.
- [x] Integrar administración de depósitos y balances paginados por depósito con `ADMIN_ALL`.
- [x] Integrar transferencias entre depósitos con `STOCK_ADJUST`, saldo de origen visible, validación y feedback transaccional.
- [x] Ajustar stock en depósito seleccionado e identificar el depósito en movimientos.

### Pedidos y ventas

- [x] Listados reales de pedidos, ventas y pagos.
- [x] Nuevo pedido con cliente/lista/productos, stock, precios y confirmación en `/api/orders/confirm`.
- [x] Seleccionar depósito para administradores; preservar `depotId` en retry exacto; omitirlo para usuarios que usan `CENTRAL` por defecto.
- [x] Preview de descuentos sin reemplazar el backend; solo `ADMIN_ALL` ve controles de descuento/precio manual.
- [x] Idempotency key, bloqueo de doble envío y retry seguro con el mismo payload.
- [x] Detalle de pedido/venta con snapshots, pagos y ledger por venta.
- [x] Registrar pagos parciales/combinados y cobros durante la entrega.
- [x] Imputación FIFO de pagos de cuenta corriente.
- [~] Edición administrativa solo para pedidos sin descuentos guardados y con una sola lista; falta diseñar preservación/reaplicación de reglas automáticas.
- [ ] Imputación de pago a deuda específica (falta consulta por cliente).
- [ ] Devolución UI (falta lectura de líneas por `saleId`).

### Entrega, documentos y calidad

- [x] Marcar pedido como entregado y registrar intentos fallidos/observaciones.
- [x] Cobrar durante entrega con efectivo/transferencia y referencia opcional.
- [x] Cancelar pedidos con confirmación/permisos y descargar PDF A4/ticket.
- [x] Solicitar notificaciones Email/WhatsApp y consultar estado individual.
- [ ] Historial de intentos de entrega (no está en la lectura de detalle).
- [ ] Panel de outbox completo (no existe endpoint operativo).
- [x] Build y proxy `/api` funcionando.
- [x] Tests Vitest de API client, componentes y flujos implementados.
- [ ] Tests E2E login -> crear cliente -> pedido -> confirmar -> ver venta.
- [~] Responsive aplicado; pruebas automatizadas mobile pendientes.

## Seguimiento de integración en `main` (2026-09-25)

Referencias remotas verificadas: `origin/main` está en `03586fd` y contiene
`0bf06c6` de `feature/backend`; `origin/feature/frontend` está en `88aa97a`.
La integración de backend y los cambios posteriores de frontend ya llegaron a
`main`. Las ramas locales antiguas y los resultados de pruebas anteriores no
describen por sí solos el estado de esa punta.

### Próximo ciclo, por prioridad y dependencias

1. [ ] **P0, CI:** diagnosticar el [run 36073594320](https://github.com/Martinrc93/distribuidoraCore/actions/runs/36073594320)
   de `0bf06c6`: el job `backend-postgres` falló en `mvn test`. El log detallado
   devolvió `403` desde este entorno; no atribuir la causa sin leerlo. Corregir
   el fallo y hacer que el workflow PostgreSQL se ejecute también en pushes/PR
   dirigidos a `main`. Cierre: run verde sobre el commit candidato de `main`,
   con SHA, número de tests, casos PostgreSQL y migración máxima registrados.
2. [ ] **P0, contrato de producto:** exigir en backend que el alta incluya
   al menos un precio para una lista activa, como pide
   [`functionalities.md`](../domain/functionalities.md). Hoy
   `ProductPayload.prices` puede omitirse y `ProductCommandService.create`
   persiste un producto sin precios. Cierre: payload ausente/vacío rechazado
   sin insertar producto; tests HTTP y PostgreSQL; alta válida desde la UI.
3. [ ] **P0, E2E integrado:** en un checkout limpio y una base PostgreSQL
   descartable, probar login → alta de cliente → producto con precio por lista
   → confirmación idempotente de pedido → detalle de venta → cobro/entrega.
   Cierre: prueba reproducible sobre `main`, con saldos, stock, permisos,
   warning de crédito y reintento verificados; sin usar `backend/target` del
   checkout de trabajo anterior.
4. [ ] **P1, proyecciones y UI comercial:** seguir las dependencias de
   [`frontend-backend-gaps.md`](frontend-backend-gaps.md): deudas por
   `customerId` antes de pago a deuda específica; líneas por `saleId` antes
   de devolución; intentos de entrega antes del historial; lectura de auditoría
   antes de su pantalla. Cierre: DTOs, permisos, errores, invalidación de
   queries y pruebas de cada flujo.
5. [ ] **P2, calidad frontend:** filtros y paginación reales en URL,
   pruebas mobile y E2E, y revisión de accesibilidad. Cierre: navegación y
   formularios verificables en desktop y mobile, sin botones inertes.

La edición de roles/permisos desde la UI sigue aplazada por decisión del usuario;
no incluirla en este ciclo sin una nueva indicación.

## Orden sugerido

El detalle de funciones completas y ausencias de API se mantiene en
[`frontend-backend-gaps.md`](frontend-backend-gaps.md).

1. [x] Completar cobros durante la entrega: método, referencia opcional de transferencia y resto a cuenta corriente.
2. [x] Imputar pagos a una deuda específica o mediante FIFO.
3. [x] Configurar límite de crédito global y advertencias auditadas por exceso.
4. [x] Crear outbox transaccional y worker con reintentos e idempotencia.
5. [x] Añadir tickets y notificaciones configurables de WhatsApp/email con auditoría.
6. [x] Implementar endurecimiento operativo: CI PostgreSQL, métricas/logs estructurados, backup/restauración, rollback, retención/purga y escrow KeePassXC sincronizado están verificados.
7. [x] Implementar historial de precios y vigencias futuras (V21), con resolución temporal y snapshots históricos.
8. [x] Implementar reglas de descuentos comerciales por línea y pedido (V22), aplicadas en confirmaciones y ediciones.
9. [x] Implementar multi-depósito (V23), transferencias atómicas y selección/restauración del depósito en el ciclo comercial.

Verificación PostgreSQL previa (2026-09-24): 314 tests, 0 fallos, 0 errores y 0
omitidos; PostgreSQL 16.4 (Flyway V1–V20, 19 casos funcionales de integración).
Suite final tras matriz de seguridad y PostgreSQL descartable
(2026-09-24, previa al backlog de precios, descuentos y depósitos): 337 tests,
0 fallos, 0 errores y 0 omitidos; los 21 casos
PostgreSQL pasaron con PostgreSQL 16.4, Flyway V1–V20 y validación JPA. ArchUnit
pasó con la regla global de ciclos. La restauración cifrada y el rechazo de
alteración HMAC se comprobaron previamente en una base descartable, eliminada
al finalizar.

Verificación posterior a los tres ítems de backlog (2026-09-24): **350 tests,
0 fallos, 0 errores y 0 omitidos**, incluidos 24 casos PostgreSQL 16.4 con
Flyway V1–V23, validación JPA y ArchUnit global en una base descartable nueva.

### Próximas tareas backend

- [x] Corregir Task Scheduler y activar la tarea diaria de backup; ejecución de control exitosa, siguiente corrida 2026-09-25 03:00.
- [x] Completar la copia externa y su recuperación: backup confirmado en OneDrive, restore/HMAC validados y clave DPAPI guardada en la bóveda real de KeePassXC 2.7.12. El usuario confirmó `RESULT=OK`; Cloud Files reportó `0x00000009` (`PLACEHOLDER` + `InSync`) para la bóveda.
- [x] Añadir ArchUnit y reglas incrementales de límites entre capas.
- [x] Eliminar el ciclo identificado y las dependencias aplicación→DTOs API; revisar el grafo completo y protegerlo con la regla global sin ciclos.
- [x] Ampliar cobertura HTTP/controller con 14 casos `MockMvc` en siete controladores.
- [x] Ejecutar una matriz inicial con cadena de seguridad real (401/403/permitido) y repetir los flujos persistentes PostgreSQL opt-in sobre una base descartable.
- [x] Ampliar la matriz HTTP a rutas críticas y permisos funcionales; recorrer login, cambios de rol/permisos y revocación JWT con PostgreSQL descartable.
- [x] Revisar el grafo completo, resolver los ciclos encontrados, registrar los paquetes aislados (`demo`, `payment`) y habilitar la regla ArchUnit global.
