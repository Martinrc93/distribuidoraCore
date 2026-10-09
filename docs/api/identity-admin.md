# Administración de usuarios, roles y permisos

Los recursos administrativos requieren `ADMIN_ALL`, salvo alta/invitación,
bloqueo/desbloqueo y revocación de sesiones, que admiten `USER_MANAGE` o
`ADMIN_ALL`. Los endpoints usan DTOs y nunca exponen hashes de contraseñas ni
tokens persistidos.

## Usuarios

`GET /api/users?page=0&size=20&search=` devuelve una página con `id`, `email`,
`name`, `status`, `roles` y `sellerDisplayName`. `roles` contiene los códigos
separados por coma; `sellerDisplayName` es nulo para usuarios sin perfil seller.
El tamaño se limita a 100.

`POST /api/users` crea un usuario activo con password temporal. `POST
/api/users/invite` crea una invitación de un solo uso. Ambos reciben `email`,
`role` (`ADMIN` o `SELLER`) y `displayName` cuando corresponde.

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

El usuario debe estar activo. Para asignar `SELLER` debe tener un perfil seller
activo. Para pasar a `ADMIN`, primero hay que desactivar el perfil seller. El
cambio conserva al menos un administrador activo, incrementa la versión de
sesión, revoca refresh tokens y registra auditoría.

`POST /api/users/{id}/block`, `POST /api/users/{id}/unblock` y `POST
/api/users/{id}/revoke-sessions` devuelven `204 No Content`.

## Roles y permisos

- `GET /api/roles` devuelve roles y sus códigos de permiso.
- `GET /api/permissions` devuelve el catálogo vigente.
- `PUT /api/roles/{roleCode}/permissions` reemplaza los permisos del rol:

```json
{"permissionCodes":["ORDER_CREATE","SALE_DELIVER"]}
```

El catálogo se mantiene en migraciones Flyway; los cambios requieren una
migración y no se crean permisos arbitrarios por HTTP. `ADMIN_ALL` debe
permanecer en `ADMIN`; `ADMIN_ALL` y `USER_MANAGE` no se asignan a otros roles.
Un cambio efectivo invalida sesiones de los usuarios asignados al rol y registra
auditoría.

Roles y permisos devuelven `200 OK`; datos inválidos `400`, recursos
inexistentes `404`, conflictos `409` y falta de autoridad `403`.
