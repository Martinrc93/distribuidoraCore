# Fase 5.4 — Pedidos y ventas

## Estado

- Mateo pidió commitear 5.2/5.3 y avanzar a 5.4. Se registraron dos commits en
  `support`: `0f15563` (backend/API) y `957ba83` (interfaz/documentación).
  No hubo push. Mateo autorizó el cierre de Pedidos y ventas y continuar con
  Clientes y cuentas corrientes. Cierre registrado en `45165f7`
  (`feat(permissions): separate order and sale actions from administrator access`).
- Se tomó Pedidos y ventas como primera área, siguiendo el plan de avanzar por
  áreas. No se migraron todas las restricciones administrativas de la aplicación.
- Sigue vigente la decisión de Mateo: roles personalizados limitados a datos
  asignados. La revisión de pedidos sigue reservada al administrador.

## Implementación

- V43 incorpora ORDER_EDIT, ORDER_CANCEL, ORDER_REACTIVATE, SALE_RETURN,
  ORDER_PRICE_OVERRIDE, ORDER_DISCOUNT, ORDER_DOCUMENT_EXPORT y ORDER_NOTIFY.
- ADMIN recibe los ocho. Roles ya existentes con ORDER_CREATE conservan su acceso
  previo a comprobantes mediante los dos permisos específicos de exportar/enviar.
  No reciben las otras acciones. La migración incrementa versión y revoca refresh
  tokens de usuarios afectados. Roles nuevos seleccionan permisos explícitamente.
- `PermissionAccess`/`hasPermission` autorizan acciones con permiso específico o
  ADMIN_ALL. No se usan como sustitutos de `CurrentUserAccess` para acceso global.
- Editar, cancelar, reactivar y devolver validan el alcance del pedido bajo bloqueo
  antes de operar. Los permisos no habilitan recursos ajenos ni modifican pagos,
  estados permitidos, stock, auditoría o reglas contables existentes.
- ORDER_EDIT permite conservar snapshots de precios/descuentos y cambiar cantidades.
  Solo ORDER_PRICE_OVERRIDE, ORDER_DISCOUNT y ORDER_PRICE_LIST_CHANGE permiten los
  cambios correspondientes. Se admiten snapshots antiguos con lista por código.
  No se reemplazan descuentos o precios históricos por cero por falta de permisos.
- `/api/pricing/resolve-batch` admite `orderId`: verifica ORDER_EDIT, acceso al
  pedido y coincidencia de cliente. Sin ese contexto sigue requiriendo ORDER_CREATE
  y acceso al cliente. No se requiere crear pedidos para editar uno asignado.
- UI: acciones de editar/cancelar/reactivar/devolver/exportar/enviar independientes;
  precios y descuentos editables solo con sus permisos. Conserva pickers compartidos,
  formularios, confirmaciones, valores históricos y diseño adaptable. La variante
  de cinco columnas se llama `.order-product-picker-discount`.
- Guía de UI, funcionalidades, APIs, diagrama de autorización y plan actualizados.
  Matriz de operaciones: `docs/api/commerce-permissions.md`.

## Verificaciones

- Backend: 546 pruebas correctas. Incluye matriz por permiso individual, acceso
  a pedidos/ventas ajenos, comprobantes y notificaciones, permisos revocados,
  conservación de snapshots y permisos de precios/descuentos por separado.
- Migración V42→V43 verificada en una base nueva temporal: acceso previo a
  comprobantes, no ampliación de otros permisos, revocación selectiva de sesiones
  y ejecución única. La base se elimina al terminar la prueba.
- Frontend: suite de 363 pruebas correcta; TypeScript y build correctos. Se conserva
  el aviso de tamaño del bundle. Pruebas enfocadas adicionales tras la revisión final.
- Brave: nueve E2E correctos a 320, 390 y 1280 px, ambos temas. Edición con valores
  históricos y variantes con permiso de precios o descuentos por separado; capturas
  inspeccionadas y sin desbordamiento horizontal. Configuración:
  `frontend/playwright.commerce-permissions.config.ts`.
- Se mantienen fuera de esta ejecución las mismas nueve clases del handoff de 5.3
  que requieren Docker o bases históricas separadas. No se modificaron bases reales.
- PostgreSQL desechable detenido. Logs, builds y capturas fuera del repositorio.
  Se preservan los generados previos en target/dist/node_modules y wrappers mvn.cmd.

## Continuidad

Primera área de 5.4 implementada. La continuación de Clientes/cuentas está en
[su handoff](2026-11-17-fase-5-4-clientes-cuentas-handoff.md). Stock/catálogo,
compras/proveedores y administración siguen pendientes de su trabajo por área.
No presentar toda 5.4 ni toda Fase 5 como completas. Comisiones, revendedores y
reportes también conservan sus pendientes. Nuevas áreas deben definir permisos y
alcance explícitamente; otorgar un permiso nunca debe convertir un rol asignado
en administrador global. Commit de 5.4 solo si Mateo lo solicita.
