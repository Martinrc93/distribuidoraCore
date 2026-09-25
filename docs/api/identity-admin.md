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
