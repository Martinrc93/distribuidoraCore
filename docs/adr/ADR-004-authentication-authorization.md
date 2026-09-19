# ADR-004: Autenticación local y autorización basada en permisos

## Status

Accepted

## Context

La instancia pertenece a una sola empresa y no requiere inicialmente un
proveedor corporativo externo. El sistema debe gestionar usuarios, vendedores,
administradores, roles, permisos, activación por email y revocación de sesiones.

## Decision

Se utilizará autenticación local con Spring Security y usuarios persistidos en
PostgreSQL.

Flujo de alta:

```text
Admin crea usuario
→ User INVITED
→ link de activación de un solo uso
→ usuario define contraseña
→ User ACTIVE
```

No se enviarán contraseñas por email.

Política de contraseña:

- Mínimo 8 caracteres.
- Una mayúscula.
- Una minúscula.
- Un número.
- Hash con Argon2id o BCrypt.

JWT:

- Access token con duración de 15 minutos.
- Refresh token con duración de 7 días.
- Refresh token rotativo, revocable y almacenado como hash.
- El access token no se almacenará en `localStorage`.
- El administrador podrá revocar las sesiones de un usuario.

Seguridad de cuentas:

- Tres intentos fallidos bloquean al usuario.
- Cualquier administrador puede desbloquearlo.
- El bloqueo y desbloqueo se auditan.
- El link de activación expira en 30 minutos.
- El administrador puede cambiar el email de un usuario.
- Los cambios de contraseña invalidan sesiones existentes.

Autorización:

- Los roles agrupan permisos.
- Las reglas de negocio se expresan mediante permisos concretos.
- Se utilizará autorización de métodos, por ejemplo `@PreAuthorize`.
- El administrador tendrá capacidad global equivalente a `ADMIN_ALL`.

Permisos relevantes:

```text
ORDER_CREATE
ORDER_CONFIRM
ORDER_EDIT
ORDER_EDIT_PRICE
ORDER_APPLY_DISCOUNT
SALE_CANCEL
SALE_DELIVER
STOCK_ADJUST
PAYMENT_REGISTER
PRICE_LIST_MANAGE
AUDIT_READ
USER_MANAGE
```

Un vendedor puede trabajar con clientes asignados, crear y confirmar pedidos,
registrar pagos y marcar entregas. No puede modificar pedidos, precios,
descuentos, stock, listas ni cancelar ventas.

## Alternatives considered

### Keycloak, LDAP u OIDC desde el inicio

Descartados porque no existe un requisito corporativo de SSO y agregarían
infraestructura operativa.

### Sesiones server-side

Más simples en algunos escenarios, pero se elige JWT por la separación entre
frontend y API y la posibilidad de incorporar otros clientes posteriormente.

### Contraseña temporal enviada por email

Descartada por riesgo de exposición de credenciales. Se usa activación mediante
token de un solo uso.

## Consequences

### Positivas

- Autenticación independiente de proveedores externos.
- Revocación explícita de refresh tokens.
- Autorización centralizada y auditable.
- Preparado para agregar OIDC en el futuro.

### Negativas

- JWT y refresh tokens requieren rotación, revocación y manejo de expiración.
- Cambios de permisos pueden tardar hasta la renovación del access token.
- Email y gestión de tokens pasan a ser responsabilidades del sistema.

## Pending decisions

- Proveedor gratuito definitivo de email; se recomienda Brevo Free.
- Política de rate limiting del login.
- Cantidad máxima de sesiones simultáneas.
