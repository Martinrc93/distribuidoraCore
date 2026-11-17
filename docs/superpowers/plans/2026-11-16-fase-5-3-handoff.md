# Fase 5.3 — roles personalizados

## Estado y decisiones

- Rama `support`. El 2026-11-17 Mateo autorizó los commits de 5.2 y 5.3 y avanzar
  con 5.4. Backend/API registrado en `0f15563`; interfaz y documentación se
  registran junto con esta actualización. No hubo push.
- Mateo confirmó que los roles personalizados quedan limitados a datos
  asignados. No hay un modo de acceso global para estos roles.
- No se agregaron dependencias ni migraciones: roles y permisos ya son dinámicos
  en la base. Los cambios usan el modelo de perfiles y asignaciones existente.

## Implementación

- `POST /api/roles`, solo `ADMIN_ALL`: código único, nombre y permisos del catálogo
  (pueden estar vacíos). Rechaza códigos reservados, permisos inexistentes y
  `ADMIN_ALL`, `USER_MANAGE`, `ORDER_REVIEW`. Transacción y auditoría `ROLE_CREATE`.
- «Crear rol» en Roles y permisos, con casillas compartidas con la edición,
  confirmación, descarte, errores recuperables y restricciones explicadas.
- Altas e invitaciones aceptan códigos de roles existentes. Todo rol distinto
  de ADMIN requiere nombre y crea un perfil seller activo en la misma transacción,
  sin agregar SELLER ni heredar sus permisos. La API informa `requiresSellerProfile`.
- Usuarios consulta el catálogo completo y permite cambiar el rol de usuarios
  activos, con confirmación y compatibilidad del estado del perfil. La lectura
  incorpora `sellerStatus`. Cambiar conserva perfil, clientes, pedidos e historial;
  incrementa versión, revoca refresh tokens y audita. El cambio propio limpia
  credenciales/caché y vuelve al login con aviso. Se preserva el último admin.
- Un perfil activo es obligatorio para cualquier rol asignado; antes de pasar
  a ADMIN se desactiva el perfil desde Vendedores. Crear un perfil para un usuario
  que ya tiene un rol personalizado tampoco agrega SELLER de manera implícita.
- Todos los usuarios sin ADMIN_ALL mantienen el alcance seller existente: filtros
  de asignación antes de contar/paginar y protección de accesos directos. No se
  habilitan pantallas administrativas por seleccionar permisos individuales.
- «Nuevo pedido» y `/orders/new` requieren ORDER_CREATE o ADMIN_ALL. El resto de
  los cambios de permisos por área sigue siendo 5.4, bajo pedido expreso.
- Se actualizaron API, funcionalidades, guía de UI y plan general.

## Verificaciones

- Backend: 533 pruebas correctas, incluidas las pruebas PostgreSQL de creación,
  duplicados, restricciones, cambio de rol, revocación de JWT/refresh y preservación
  de asignaciones. La batería de alcance se ejecuta con SELLER y con un rol
  personalizado, incluyendo clientes, pedidos, ventas, totales, filtros, acceso
  por ID/número y documentos. PostgreSQL desechable detenido al finalizar.
- Frontend: 358 pruebas correctas (44 archivos). TypeScript y build correctos.
  El build conserva el aviso previo por tamaño del bundle principal.
- Navegador: nueve E2E correctos en Brave, a 320, 390 y 1280 px, con ambos temas,
  creación, edición, asignación, confirmación, descarte y cierre de sesión propia.
  Capturas inspeccionadas en escritorio y móvil; sin desbordamiento horizontal.
- No se ejecutaron siete clases que requieren Docker ni dos migraciones históricas
  que necesitan bases vacías separadas: `DemoDataSeederPostgresTest`,
  `SingleInventoryMigrationIntegrationTest`, `DashboardAnalyticsPostgresTest`,
  `SupplierOrderPostgresTest`, `CustomerOrderNumberPostgresTest`,
  `InvitationOutboxPostgresTest`, `CustomerNumberCapacityPostgresTest`,
  `ProductDescriptionPostgresTest`, `ProductSkuRemovalPostgresTest`.
- Builds, logs y capturas fuera del repositorio, en el directorio de visualizaciones
  de este chat. Se preservaron los generados y wrappers que ya estaban en el árbol.

## Continuidad

5.3 completa. 5.1 sigue parcial; 5.4 debe abordarse por área cuando Mateo lo pida.
Crear roles no implementa todavía comisiones, revendedores ni reportes. No presentar
la Fase 5 completa como terminada. Antes de editar UI, leer la guía aplicable.

Comandos y entorno: Maven/Java/PostgreSQL y las exclusiones están documentados en
el handoff de 4.5. E2E: `playwright test --config playwright.roles.config.ts`, usando
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` para Brave. Mantener builds aislados y no
incluir `target`, `dist`, `node_modules` ni wrappers `mvn.cmd` en futuros commits.
