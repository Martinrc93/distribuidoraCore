# Plan de Implementación: Invitación, Activación y Revocación Administrativa de Sesiones

## Objetivo

Implementar el flujo completo de ciclo de vida de usuarios administrativos y comerciales:
1. Invitación de usuarios en estado inicial `INVITED`.
2. Emisión y verificación de tokens de activación seguros (hash SHA-256) de un solo uso con expiración a 30 minutos.
3. Activación pública mediante establecimiento de contraseña inicial (`POST /api/auth/activate`).
4. Revocación administrativa inmediata de todas las sesiones de un usuario (`POST /api/users/{id}/revoke-sessions`).
5. Bloqueo y desbloqueo administrativo de cuentas (`POST /api/users/{id}/block`, `POST /api/users/{id}/unblock`).
6. Auditoría integral de cada transición de estado de usuario.

---

## Componentes y Cambios

### 1. Migración de Base de Datos
- **Archivo:** `backend/src/main/resources/db/migration/V13__create_user_activation_tokens_table.sql`
- **Contenido:**
  ```sql
  CREATE TABLE identity.user_activation_tokens (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES identity.users (id),
      token_hash VARCHAR(128) NOT NULL UNIQUE,
      expires_at TIMESTAMPTZ NOT NULL,
      used_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL
  );
  CREATE INDEX ix_user_activation_tokens_user_id ON identity.user_activation_tokens (user_id);
  ```

### 2. DTOs y Contratos de API
- **Archivo:** `UserAdminDtos.java`
  - `InviteUserRequest(@NotBlank @Email String email, @NotNull Role role, String displayName)`
  - `InviteUserResponse(UUID userId, String email, String activationToken, Instant expiresAt)`
- **Archivo:** `AuthDtos.java`
  - `ActivateUserRequest(@NotBlank String activationToken, @NotBlank @Size(min = 8, max = 200) String password)`

### 3. Excepciones y Manejo de Errores
- **Archivo:** `InvalidActivationTokenException.java`
- **Archivo:** `ApiExceptionHandler.java`:
  - Mapear `InvalidActivationTokenException` a HTTP 400 `INVALID_ACTIVATION_TOKEN`.

### 4. Lógica de Aplicación
- **Archivo:** `UserAdminService.java`
  - `invite(InviteUserRequest)`:
    - Valida que email no exista.
    - Crea usuario en estado `INVITED` con hash unmatchable.
    - Si es `SELLER`, crea perfil en `seller.seller_profiles`.
    - Asigna rol en `identity.user_roles`.
    - Genera token aleatorio de 32 bytes Base64URL, calcula hash SHA-256 y persiste en `identity.user_activation_tokens` (30 min).
    - Audita `USER_INVITE`.
    - Retorna `InviteUserResponse`.
  - `revokeSessions(UUID userId)`:
    - Revoca todos los refresh tokens activos del usuario: `refreshTokenRepository.revokeAllByUserId(userId, Instant.now())`.
    - Audita `REVOKE_SESSIONS`.
  - `blockUser(UUID userId)`:
    - Cambia status a `BLOCKED`.
    - Revoca todas las sesiones activas del usuario.
    - Audita `USER_BLOCK`.
  - `unblockUser(UUID userId)`:
    - Cambia status a `ACTIVE`, limpia intentos fallidos y `locked_until`.
    - Audita `USER_UNBLOCK`.
- **Archivo:** `AuthService.java`
  - `activate(ActivateUserRequest)`:
    - Calcula hash SHA-256 del token provisto.
    - Busca en `identity.user_activation_tokens`.
    - Valida que exista, no esté usado y no esté expirado.
    - Actualiza usuario a `ACTIVE` con el hash de la contraseña ingresada.
    - Marca token como `used_at = Instant.now()`.
    - Audita `USER_ACTIVATE`.

### 5. Controladores y Configuración de Seguridad
- **Archivo:** `UserAdminController.java`:
  - `POST /api/users/invite`: `@PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")`
  - `POST /api/users/{id}/revoke-sessions`: `@PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")`
  - `POST /api/users/{id}/block`: `@PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")`
  - `POST /api/users/{id}/unblock`: `@PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")`
- **Archivo:** `AuthController.java`:
  - `POST /api/auth/activate`: recibe `ActivatePayload` y llama `authService.activate(...)`.
- **Archivo:** `SecurityConfig.java`:
  - Agregar `/api/auth/activate` a `permitAll()`.

---

## Verificación
- `UserActivationTokenMigrationContractTest.java`: valida script V13.
- `UserAdminServiceTest.java`: prueba invitación, revocación de sesiones, bloqueo y desbloqueo.
- `AuthServiceTest.java`: prueba activación exitosa, token expirado, token ya usado o inválido.
- `UserAdminControllerTest.java`: prueba HTTP 201 en invite, 204 en bloqueos y revocación.
- `AuthControllerTest.java`: prueba HTTP 204 en `/api/auth/activate`.
- Regresión completa de la suite: `.\mvn.cmd test`.
