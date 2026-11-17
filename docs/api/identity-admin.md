# Administración de usuarios, roles y permisos

Los recursos administrativos requieren `ADMIN_ALL`, salvo alta/invitación,
bloqueo/desbloqueo y revocación de sesiones, que admiten `USER_MANAGE` o
`ADMIN_ALL`. Los endpoints usan DTOs y nunca exponen hashes de contraseñas ni
tokens persistidos.

## Usuarios

`GET /api/users?page=0&size=20&search=` devuelve una página con `id`, `email`,
`name`, `status`, `roles`, `sellerDisplayName` y `sellerStatus`. `roles` contiene los códigos
separados por coma; `sellerDisplayName` es nulo para usuarios sin perfil seller.
El tamaño se limita a 100.

`POST /api/users` crea un usuario activo con password temporal. `POST
/api/users/invite` crea una invitación de un solo uso. Ambos reciben `email`,
`role` (código existente, incluido un rol personalizado) y `displayName` cuando corresponde.
Todo rol distinto de `ADMIN` requiere nombre y crea un perfil seller activo en la
misma transacción para vincular sus datos asignados. Solo se asigna el rol elegido;
no se agrega `SELLER` ni se heredan sus permisos. Un código inexistente devuelve
`404` sin crear la cuenta. Los códigos admiten de 2 a 80 letras mayúsculas,
números o guiones bajos, comenzando con una letra.

La invitación crea la cuenta en estado `INVITED`, guarda el hash del token y
encola el correo en la misma transacción. La respuesta es
`{userId, email, deliveryStatus: "QUEUED", expiresAt}`; no devuelve el token.
El correo contiene un enlace a `/activate?token=...` para elegir la contraseña.
La pantalla de activación solicita «Contraseña» y «Repetir contraseña», ambas
obligatorias. Solo envía el token y la contraseña cuando los valores coinciden;
si difieren, muestra un error asociado al campo de confirmación y permite corregirlo.
El token es de un solo uso y vence a los 30 minutos de crear la invitación.
La contraseña provisoria sigue siendo una alternativa independiente, sin correo.

Configurar `APP_PUBLIC_URL` con el origen público de la interfaz (por ejemplo,
`https://app.example.com`), `EMAIL_WEBHOOK_URL` y, si corresponde,
`EMAIL_WEBHOOK_TOKEN`. Se permite HTTP en localhost para desarrollo.
Sin origen válido o proveedor de email, devuelve `503` con código
`INVITATION_EMAIL_UNAVAILABLE`, sin crear la cuenta.

El worker de outbox envía después del commit y reintenta fallos del proveedor;
`QUEUED` no confirma recepción ni entrega. El proveedor debe aceptar el cuerpo
de email de texto descrito en [notifications.md](notifications.md) y deduplicar
por `Idempotency-Key`. Los enlaces vencidos no se envían. El enlace permanece
temporalmente en la cola para reintentos y se elimina al procesar el evento o
agotar los intentos; la tabla de activaciones conserva solo el hash.
La interfaz informa envío pendiente; todavía no hay consulta de estado ni
reenvío de invitaciones desde la pantalla de usuarios.

`PUT /api/users/{id}/role` reemplaza el rol único del usuario:

```json
{"role":"ADMIN"}
```

El usuario debe estar activo. Para asignar cualquier rol distinto de `ADMIN` debe tener un perfil seller
activo. Para pasar a `ADMIN`, primero hay que desactivar el perfil seller. El
cambio conserva al menos un administrador activo, incrementa la versión de
sesión, revoca refresh tokens y registra auditoría. Conserva el perfil, las
asignaciones y el historial. Repetir su único rol actual no invalida sesiones.
La pantalla Usuarios ofrece los roles del catálogo completo, deshabilita los
incompatibles con el estado del perfil y confirma antes de reemplazar los roles
actuales por uno. Si cambia el usuario autenticado, limpia credenciales y caché
y vuelve al login con el resultado.

`POST /api/users/{id}/block`, `POST /api/users/{id}/unblock` y `POST
/api/users/{id}/revoke-sessions` devuelven `204 No Content`.

## Roles y permisos

- `GET /api/roles` devuelve roles y sus códigos de permiso, `requiredPermissions`
  (permisos que el rol debe conservar) y `assignedToCurrentUser` (el usuario
  autenticado pertenece al rol). `currentSessionRevoked` es `false` en esta lectura.
  `requiresSellerProfile` es `true` para todo rol distinto de `ADMIN`.
- `GET /api/permissions` devuelve el catálogo vigente con `code`, `description`
  y `adminOnly`. Esta última propiedad usa las mismas restricciones que la escritura.
- `PUT /api/roles/{roleCode}/permissions` reemplaza los permisos del rol:

```json
{"permissionCodes":["ORDER_CREATE","SALE_DELIVER"],"expectedPermissionCodes":["ORDER_CREATE"]}
```

El catálogo se mantiene en migraciones Flyway; los cambios requieren una
migración y no se crean permisos arbitrarios por HTTP. `ADMIN_ALL` debe
permanecer en `ADMIN`; `ADMIN_ALL`, `USER_MANAGE` y `ORDER_REVIEW` no se asignan a otros roles.
Un cambio efectivo invalida sesiones de los usuarios asignados al rol y registra
auditoría.

La pantalla envía `expectedPermissionCodes` con la selección original. El servidor
compara ese conjunto bajo el bloqueo del rol antes de guardar; si otro administrador
lo cambió, responde `409` sin escribir permisos, invalidar sesiones ni generar
auditoría de actualización. El campo es opcional para mantener el contrato de
los clientes existentes; omitirlo conserva el reemplazo completo sin comparación.

La respuesta del `PUT` conserva los datos del rol y agrega `currentSessionRevoked`:
`true` solo si hubo un cambio efectivo y el usuario autenticado pertenece al rol.
La interfaz limpia credenciales y caché y muestra el login con el resultado del
guardado. Una actualización sin cambios no invalida sesiones ni duplica auditoría.

### Pantalla de roles (Fase 5.2)

`/admin/roles`, en Administración → Roles y permisos, requiere `ADMIN_ALL` tanto
en navegación/ruta como en los endpoints. Consulta roles y catálogo completos;
si falla una lectura, permite reintentar y no ofrece una edición parcial.

La edición muestra los permisos asignados en un modal. Los obligatorios quedan
marcados y bloqueados; los reservados a administradores no se pueden asignar a
otros roles. Antes de guardar presenta qué permisos se agregan y quitan y avisa
del cierre de sesiones. Cancelar o Escape conservan el borrador hasta confirmar
su descarte. Un error de guardado permite reintentar; un conflicto conserva el
borrador y exige cerrar/recargar antes de otra edición.

`ADMIN_ALL` mantiene acceso completo aunque se quiten permisos individuales.
Asignar un permiso no cambia el rol ni abre pantallas reservadas a administradores.
El desacoplamiento de `ADMIN_ALL` en 5.4 incluye [Pedidos y ventas](commerce-permissions.md)
y [Clientes y cuentas corrientes](customer-permissions.md). Stock/catálogo,
compras/proveedores y administración siguen pendientes.

### Alta de roles personalizados (Fase 5.3)

`POST /api/roles`, exclusivo de `ADMIN_ALL`, recibe:

```json
{"code":"LOGISTICS","description":"Logística","permissionCodes":["ORDER_PREPARE"]}
```

Devuelve `201` con el rol y los mismos metadatos de la lectura. `description`
es obligatorio (máximo 255 caracteres); `code` debe ser único y cumplir el formato
anterior. `ADMIN` y `SELLER` son códigos reservados. `permissionCodes` es obligatorio
y puede estar vacío; solo acepta permisos del catálogo, excluyendo `ADMIN_ALL`,
`USER_MANAGE` y `ORDER_REVIEW`. Un duplicado devuelve `409`; datos inválidos, `400`.
La creación es transaccional y audita `ROLE_CREATE` con alcance `ASSIGNED`.

«Crear rol» reutiliza las casillas de permisos de la edición, explica el alcance
y pide confirmación antes de guardar. El rol nuevo queda disponible en el alta,
la invitación y el cambio de rol de usuarios, sin modificar cuentas existentes.
Los roles personalizados tienen el mismo alcance que un vendedor: clientes,
pedidos y ventas asignados a su perfil, con filtros antes de contar y paginar y
protección del acceso directo por ID, número y documentos. No reciben acceso
global por seleccionar permisos. «Nuevo pedido» y su ruta requieren `ORDER_CREATE`
o `ADMIN_ALL`. Las pantallas reservadas a administradores mantienen sus reglas
hasta su trabajo por área de 5.4. Las acciones de Pedidos y ventas ya usan
permisos específicos sin ampliar el alcance asignado, al igual que Clientes y
cuentas corrientes. V44 añade siete permisos de cliente: inicialmente solo ADMIN,
salvo exportación del estado de deuda, que conserva el acceso de roles existentes
con SALE_PAYMENT. Se pueden asignar desde el catálogo; crear clientes con alcance
asignado los vincula al perfil propio y no autoriza reasignaciones globales.

V42 agrega `ORDER_PREPARE` (asignado inicialmente a `ADMIN` y `SELLER`) y
`ORDER_REVIEW` (solo `ADMIN`). Son permisos propios, integrados a este catálogo.
La preparación no depende de `SALE_DELIVER`; este último autoriza la preparación
implícita cuando se entrega un pedido pendiente. La revisión exige administrador
en backend y nunca bloquea la operación comercial del vendedor.

Roles y permisos devuelven `200 OK`; datos inválidos `400`, recursos
inexistentes `404`, conflictos `409` y falta de autoridad `403`.
