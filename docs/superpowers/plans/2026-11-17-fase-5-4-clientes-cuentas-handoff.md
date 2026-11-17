# Fase 5.4 — Clientes y cuentas corrientes

## Estado

- Mateo pidió «Commitea y avanza», siguiendo la propuesta de cerrar Pedidos y
  ventas y continuar por Clientes/cuentas. Se registró `45165f7`
  (`feat(permissions): separate order and sale actions from administrator access`)
  en `support`. En el turno siguiente, Clientes y cuentas corrientes quedó
  registrado en `b6973f9` (`feat(permissions): scope customer and account actions to assigned data`).
- Sigue vigente la decisión de Mateo: roles personalizados limitados a datos
  asignados, sin permisos que otorguen acceso global implícitamente. Se usan
  permisos permanentes del catálogo y validaciones de alcance en backend.
- Segunda área de 5.4 implementada. La fase completa sigue en curso.

## Implementación

- V44 agrega CUSTOMER_VIEW, CUSTOMER_CREATE, CUSTOMER_EDIT,
  CUSTOMER_STATUS_CHANGE, CUSTOMER_PRICE_LIST_CHANGE, ACCOUNT_SETTLE y
  CUSTOMER_STATEMENT_EXPORT. ADMIN recibe los siete. Roles existentes con
  SALE_PAYMENT reciben solo la exportación para conservar su acceso previo a
  documentos; no reciben las otras acciones. Se invalidan las sesiones de los
  usuarios afectados una vez. El catálogo de Roles ya permite configurarlos.
- El listado básico conserva acceso autenticado y alcance asignado. Ficha e
  historial requieren CUSTOMER_VIEW. Las acciones y sus rutas ya no dependen de
  ADMIN_ALL; el filtro y catálogo global de vendedores conservan esa restricción.
- Alta asignada vinculada al perfil propio activo, incluso si se omite sellerId.
  Enviar otro vendedor devuelve 403. Escrituras por ID bloquean el cliente y
  verifican alcance antes de cambiar datos; cliente ajeno devuelve 404. Auditoría
  de clientes dentro de la misma transacción, sin éxito auditado tras rollback.
- CUSTOMER_EDIT no concede cambios de lista: conservar su ID previo permite
  editar contactos, incluso con una lista histórica inactiva. Cambiarla o quitarla
  exige CUSTOMER_PRICE_LIST_CHANGE, también si se envía dentro del formulario
  completo; asignarla en un alta exige ese permiso adicional. Es independiente
  de ORDER_PRICE_LIST_CHANGE. No se modifican precios históricos.
- `CustomerAccountBalanceQuery` unifica saldo de listado, ficha y cobros. Sin
  ADMIN_ALL se suman deudas de ventas de pedidos propios o sin vendedor del
  cliente asignado, limitadas al saldo global positivo. Cada venta se limita por
  ledger e impago. `hasBalance` filtra ese saldo antes de contar/paginar. Historial
  filtra pedidos propios antes de contar/paginar. ADMIN conserva saldo global y
  créditos. No se exponen deudas exclusivas de otro vendedor.
- ACCOUNT_SETTLE habilita pagos combinados/FIFO parciales o completos de esa deuda
  visible sin exigir SALE_PAYMENT ni CUSTOMER_VIEW. Pagos, ledger, saldo global y
  auditoría siguen siendo atómicos. Liquidar deuda asignada puede dejar deuda de
  otro vendedor. Se bloquean pedidos/ventas y cliente y se revalida alcance.
- Lecturas auxiliares independientes: `/api/customers/{id}/account-balance`
  (ACCOUNT_SETTLE/SALE_PAYMENT/ADMIN_ALL) devuelve solo id/name/balance/status;
  `/api/customers/assignment-context` (CUSTOMER_CREATE/EDIT/ADMIN_ALL) devuelve solo
  el perfil propio activo. GET de zonas admite create/edit para el formulario;
  su administración permanece reservada a ADMIN_ALL.
- UI: acciones independientes de ficha, editar, estado, lista, cobro y exportación;
  no se necesita ficha para cobrar o exportar. SellerSelect compartido, propio y
  bloqueado para alcance asignado. Alta/edición con FormModal nativo, descarte,
  Escape, protección de salida, reintento de metadatos y bloqueo al guardar.
  Lista predeterminada con diálogo compacto independiente. Estado con
  ConfirmationDialog y foco al botón o búsqueda cuando la fila deja el filtro.
  La ficha distingue «Saldo de tus ventas» y documentos por permiso propio.
- Contratos, guía UI, funcionalidades y plan actualizados. Matriz completa:
  [Clientes y cuentas](../../api/customer-permissions.md).

## Verificaciones

- Backend: 556 pruebas correctas, sin fallos ni omisiones dentro de la selección.
  Incluye matriz de siete permisos independientes, API con JWT, asignación propia,
  acceso directo a clientes ajenos, perfil inactivo, historial y hasBalance con
  deuda de otros vendedores, revocación, pagos parciales/combinados y rollback por
  exceso. Una regresión adicional comprueba que editar contactos conserva la lista
  asignada, pero cambiarla, quitarla o asignarla en un alta sin su permiso devuelve
  403 sin modificar datos ni duplicar auditoría. Conserva las pruebas contables y
  de concurrencia existentes.
- Migración V43→V44 ejecutada en una base temporal nueva: conserva exportación
  sin ampliar otras acciones, invalida selectivamente sesiones y no se repite.
  El test de V43 se fija explícitamente en target 43 para probar solo su migración.
- Frontend: suite completa de 371 pruebas (45 archivos). Tras el ajuste final de
  foco, 38 pruebas enfocadas correctas. TypeScript y build correctos; persiste el
  aviso previo del bundle mayor a 500 kB.
- Brave: diez E2E correctos con `playwright.customer-permissions.config.ts`:
  alta propia, reintento/descarte, cobro combinado sin ficha y lista independiente
  a 320, 390 y 1280 px, ambos temas; además, foco al desactivar y salir del filtro.
  Capturas de alta móvil, pago móvil oscuro y lista en escritorio inspeccionadas.
- Se mantienen excluidas nueve clases: DemoDataSeederPostgresTest,
  SingleInventoryMigrationIntegrationTest, DashboardAnalyticsPostgresTest,
  SupplierOrderPostgresTest, CustomerOrderNumberPostgresTest,
  InvitationOutboxPostgresTest, CustomerNumberCapacityPostgresTest,
  ProductDescriptionPostgresTest y ProductSkuRemovalPostgresTest. Siete requieren
  Docker y dos bases históricas separadas. La fixture de CustomerNumberCapacity
  se adapta al constructor/autenticación nuevos y compila; no se ejecutó esa clase.
- PostgreSQL desechable detenido. No se modificaron bases reales. Logs, builds y
  capturas fuera del repositorio; se preservan target/dist/node_modules y wrappers
  mvn.cmd preexistentes. No incluirlos en el siguiente commit.

## Entorno y comandos

El directorio aislado de este chat es
`C:/Users/Mateo/.codex/visualizations/2026/11/16/01a1e5ad-3d74-7d80-8c66-8ac54210a098`.
Maven local: `C:/Users/Mateo/.maven/apache-maven-3.9.9/bin/mvn.cmd`.
Usar `-Disolated.build.dir=<directorio>/backend-build` y, con Java 26, el agente
Mockito 5.17.0 y `-Dnet.bytebuddy.experimental=true` como en los handoffs previos.
La suite PostgreSQL usa POSTGRES_TEST_URL/USERNAME del clúster desechable en
55432. Mantener exclusiones anteriores y arrancar/detener el clúster en el mismo
comando; no usar configuración de bases reales. En Windows, el arranque directo
de pg_ctl con redirección quedó esperando tras anunciar disponibilidad; separar
la ejecución de Maven del ciclo del servidor hizo que se perdiera la conexión.
La ejecución final correcta usa Start-Process con WindowStyle Hidden y PassThru,
espera solo al proceso pg_ctl con WaitForExit(15000), verifica pg_isready, ejecuta
Maven y detiene PostgreSQL en finally. No usar Start-Process -Wait para el arranque,
porque espera también a los descendientes que deben seguir activos durante las pruebas.

Frontend desde `frontend`: `vitest run`, `npm run build -- --outDir <directorio>/frontend-customers-build`
y `playwright test --config playwright.customer-permissions.config.ts --output <directorio>/customer-permissions-e2e`.
Configurar PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH con Brave instalado. Los E2E usan
API simulada; las reglas de autorización/contabilidad se verifican en PostgreSQL.

## Continuidad

La continuación revisó Stock/catálogo, Compras y Administración. V45 retira
`STOCK_ADJUST` y `USER_MANAGE` de roles distintos de ADMIN y cierra las sesiones
afectadas. Las pruebas de autorización cubren inventario global, compras,
proveedores, usuarios, roles y auditoría. Los precios para pedidos validan el
cliente asignado y el contexto de creación o edición. La consulta operativa
conserva disponibilidad de productos para esos dos permisos.

Verificación de V45 y del cierre de 5.4. Pasaron 558 pruebas de backend con las
nueve clases anteriores excluidas, 372 de frontend, cuatro E2E de Stock, nueve
de Roles y seis de Compras. La compilación de frontend pasó. PostgreSQL
desechable se detuvo tras la prueba. El cierre quedó registrado en `6bd6cfa`.

El [plan general](2026-11-12-requerimientos-isma-leo.md) registra el cierre de
5.4 y la implementación de Mermas (6.1). La revisión de pedidos sigue a cargo
del administrador. Comisiones, revendedores y reportes conservan sus pendientes.
