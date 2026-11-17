# Fase 5.2 — roles y permisos

Actualización posterior: 5.3 también fue implementada, con roles personalizados
limitados a datos asignados. Ver `2026-11-16-fase-5-3-handoff.md` para el estado vigente;
este documento conserva el cierre y las verificaciones de 5.2.

## Estado

- Rama `support`. A pedido de Mateo se commiteó primero 4.5 en `3564e74`
  (`feat(orders): separate preparation and administrative review`). No se hizo push.
- Fase 5.2 implementada y verificada. El 2026-11-17 Mateo autorizó registrar 5.2
  y 5.3; backend/API en `0f15563`, interfaz/documentación junto con esta actualización.
- Mantener fuera de commits los archivos generados y wrappers `mvn.cmd`.
- Cada función debe tener permisos propios; Mateo rechazó soluciones temporales.

## Implementación

- `RolesPage` en `/admin/roles`, entrada Administración → Roles y permisos,
  protegida con `ADMIN_ALL`. El alta de roles se agregó después en 5.3.
- Consulta roles y catálogo completos. Edita en `FormModal`, muestra restricciones,
  diferencias y una confirmación antes de guardar. Tiene carga, vacío, reintento,
  descarte, bloqueo durante guardado y recuperación de errores.
- Las restricciones vienen del backend: `requiredPermissions` por rol y
  `adminOnly` por permiso. La escritura usa la misma política administrativa.
- `expectedPermissionCodes` compara la selección original bajo bloqueo del rol;
  una edición desactualizada devuelve `409` sin cambios ni revocación de sesiones.
  La interfaz conserva el borrador hasta descartar y recargar explícitamente.
- `assignedToCurrentUser` anticipa el efecto sobre la sesión propia;
  `currentSessionRevoked` en el resultado confirma si realmente se invalidó.
  En ese caso se borran credenciales y caché y se vuelve al login con el resultado.
- No hubo migraciones nuevas ni dependencias agregadas. La API mantiene el
  reemplazo existente si se omite el campo opcional de comparación.
- `ADMIN_ALL` conserva acceso completo; asignar permisos individuales no abre
  pantallas reservadas a administradores. Se explica en la interfaz.

## Verificaciones

- Backend: 526 pruebas correctas con PostgreSQL desechable. Incluye contratos,
  restricciones, conflictos, actualizaciones sin cambios e invalidación de sesión.
- Frontend: 349 pruebas correctas (43 archivos), TypeScript y build correctos.
- Brave: cinco E2E correctos a 320, 390 y 1280 px, incluido tema oscuro,
  navegación, foco, Escape/descarte, confirmación y cierre de sesión propia.
  Configuración: `frontend/playwright.roles.config.ts`.
- Navegación: 19 E2E correctos entre 320 y 1920 px, incluidas pantallas de poca
  altura, teclado, cambios de tamaño, temas y cierre de sesión.
- Se excluyeron las mismas siete clases que requieren Docker y las dos
  migraciones históricas que necesitan bases vacías separadas, enumeradas en el
  handoff de 4.5. No se modificaron bases reales. PostgreSQL desechable detenido.
- Builds, logs y capturas se mantuvieron fuera de los cambios versionables.

## Continuidad

5.3 fue implementada después de este cierre: selección en Usuarios, perfiles
seller y alcance limitado a datos asignados, confirmado por Mateo. Ver su handoff
para la implementación y verificaciones actuales. Crear un rol no concede acceso
a pantallas administrativas: 5.4 es un trabajo separado por área, bajo pedido.
Comisiones, revendedores y reportes siguen pendientes.

Antes de editar UI, leer `docs/development/ui-style-guide.md`. Commit solo cuando
Mateo lo solicite; Conventional Commits en inglés, sin atribuciones de IA.
