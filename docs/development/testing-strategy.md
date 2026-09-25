# Estrategia de testing

## Objetivo

Probar comportamiento y reglas de negocio, no detalles accidentales de
implementación.

## Backend

### Unit tests

Usar JUnit 5 y Mockito para:

- Estados de pedidos y ventas.
- Cálculo de precios y descuentos acumulables.
- Aplicación de pagos.
- Límite de crédito y advertencias.
- Reglas de stock negativo y múltiplos de `0.5`.
- Permisos y políticas de autorización.
- Tokens de activación y expiración.

### Application tests

Probar casos de uso completos con dobles controlados:

- Confirmar pedido.
- Editar pedido confirmado.
- Cancelar venta no pagada.
- Registrar pagos parciales y combinados.
- Marcar entrega.
- Registrar intento no entregado.

### Repository tests

Usar Testcontainers con PostgreSQL real para:

- Migraciones Flyway.
- Constraints e índices relevantes.
- Paginación y filtros.
- Locking del balance de stock.
- Ledger de cuenta corriente.
- Idempotency keys.

### API tests

Usar `MockMvc` para probar el contrato HTTP de controllers: binding de JSON,
serialización de respuestas, códigos de estado, formato de errores y validación
de DTOs. La cobertura representativa incluye catálogo, pagos, entregas,
devoluciones, vendedores y configuración de crédito, además de los tests HTTP
existentes de identidad, documentos, pricing y productos.

Completar progresivamente la cobertura de:

- Autenticación y permisos.
- Paginación.
- CORS.
- Idempotencia.

### Architecture tests

ArchUnit verifica en `ModuleBoundaryTest` que:

- Los controladores no dependan de paquetes `infrastructure`.
- El dominio no dependa de paquetes `api`, `application` o `infrastructure`.
- `shared` no dependa de `catalog`.
- `application` no dependa de `api`.

La dependencia `shared → catalog` que cerraba el ciclo conocido
`audit → shared → catalog → audit` fue eliminada. Tras revisar el grafo completo,
se habilitó `modulesAreFreeOfDependencyCycles`: todos los slices de producción
deben estar libres de ciclos. `ModuleBoundaryTest` importa el directorio de
clases de producción y excluye bytecode de tests y artefactos obsoletos. Las
cuatro reglas de capas más esta regla global se ejecutan en cada suite Maven.

Las pruebas `MockMvc` standalone validan el binding y el contrato del
controller, pero no cargan por sí solas la cadena de filtros de seguridad ni
PostgreSQL. Para esos escenarios se mantienen tests de integración dedicados.

`SecurityChainAuthorizationTest` importa la `SecurityConfig` de producción y
recorre `JwtAuthenticationFilter` con tokens firmados por `JwtService`; no usa
el postprocesador `user()` que omite el filtro. Sus seis casos comprueban `401`
sin token, `403` con permiso insuficiente y accesos representativos a marcas,
documentos, pagos, entregas, ajustes de inventario y administración de usuarios
con `ADMIN_ALL`, `USER_MANAGE`, `ORDER_CREATE`, `SALE_PAYMENT`, `SALE_DELIVER` y
`STOCK_ADJUST`. También comprueban que authorities funcionales ajenas no cruzan
esas rutas. El repositorio de usuarios se simula solo en este test de matriz.

`PostgresBackendFixesIntegrationTest` agrega dos flujos HTTP sobre PostgreSQL
real: login con permisos cargados de tablas `identity.*`, cambio de rol y de
permisos por los endpoints administrativos, rechazo del JWT anterior con `401`
y verificación del acceso y authorities del nuevo login. Se usa un rol temporal
único por test para no mutar los permisos semilla de `ADMIN` o `SELLER`.

Verificación del 2026-09-24 tras revisar el grafo modular: **329 tests, 0
fallos, 0 errores y 19 omitidos**. ArchUnit pasó, incluida la regla global de
ciclos. Los omitidos son los casos PostgreSQL opt-in; esta ejecución no tenía
`POSTGRES_TEST_URL`. La verificación PostgreSQL previa se registra por separado
en el estado del backend.

`PostgresBackendFixesIntegrationTest` habilita sus 24 casos cuando se define
`POSTGRES_TEST_URL`; activa Flyway y `ddl-auto=validate`, por lo que debe apuntar
siempre a una base descartable. Verificación completa previa del 2026-09-24:
**337 tests, 0 fallos, 0 errores y 0 omitidos**; los 21 casos PostgreSQL pasaron
en PostgreSQL 16.4 con una base nueva, migraciones V1–V20 y validación JPA.

Verificación incremental de precios del 2026-09-24: **31 tests dirigidos** de
pricing/productos y **22 casos PostgreSQL** pasaron con Flyway V1–V21 y
`ddl-auto=validate`. Se cubrieron precio actual, vigencia futura, resolución
por fecha, rechazo bajo costo y cancelación. Los tres ítems del backlog autorizado
se cerraron con la verificación incremental registrada abajo; la verificación
Maven completa de cierre se ejecuta después de estos cambios.

Verificación incremental de reglas comerciales del 2026-09-24: **31 tests
dirigidos** y **23 casos PostgreSQL** pasaron con Flyway V1–V22 y
`ddl-auto=validate`. Se cubrieron creación/validación, selección por prioridad
y alcance, aplicación al confirmar y persistencia de snapshots tras desactivar
la regla.

Verificación incremental multi-depósito del 2026-09-24: **56 tests dirigidos**
(incluida la matriz HTTP de permisos) y **24 casos PostgreSQL** pasaron con
Flyway V1–V23 y `ddl-auto=validate`. Se cubrieron migración de saldos existentes,
transferencia atómica, saldo insuficiente, pedido, devolución y cancelación en
el depósito seleccionado, consultas agregadas y autorización de rutas.

Verificación completa de cierre (2026-09-24): **350 tests, 0 fallos, 0 errores
y 0 omitidos**, incluidos los 24 casos PostgreSQL en PostgreSQL 16.4 con Flyway
V1–V23 y validación JPA sobre una base descartable nueva. ArchUnit pasó incluida
la regla global de ciclos entre módulos.

## Frontend

- Vitest para funciones puras.
- React Testing Library para comportamiento de componentes.
- MSW o equivalente para mocks HTTP.
- Playwright para flujos críticos.

Flujos E2E iniciales:

- Activación de usuario.
- Login y bloqueo.
- Crear y confirmar pedido.
- Confirmación con stock negativo.
- Pago parcial.
- Pago combinado.
- Advertencia de límite de crédito.
- Entrega e intento no entregado.
- Cancelación de venta no pagada.
- Descarga e impresión de documento.

## Qué no testear de forma excesiva

- Getters y setters triviales.
- Código generado por MapStruct.
- Configuración estándar de Spring sin reglas propias.
- Snapshots visuales masivos sin comportamiento asociado.

## Criterio de aceptación

Un cambio no se considera completo hasta tener tests adecuados para reglas
modificadas, migraciones probadas y verificación de regresión relevante.
