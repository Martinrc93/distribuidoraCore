# Implementación: Refresh Tokens Rotativos y Revocables

## Objetivo

Implementar un mecanismo de refresh tokens rotativos y revocables en el backend para permitir la renovación segura de access tokens JWT sin obligar a los usuarios a reingresar credenciales frecuentemente, minimizando el riesgo de robo o reuso de sesiones mediante almacenamiento como hash SHA-256, rotación en cada uso y detección activa de reuso.

## Contexto y Estado Actual

- `V2__create_identity_and_audit_tables.sql` ya creó la tabla `identity.refresh_tokens` con:
  - `id UUID PRIMARY KEY`
  - `user_id UUID NOT NULL REFERENCES identity.users (id)`
  - `token_hash VARCHAR(128) NOT NULL UNIQUE`
  - `expires_at TIMESTAMPTZ NOT NULL`
  - `revoked_at TIMESTAMPTZ`
  - `created_at TIMESTAMPTZ NOT NULL`
- `AuthService.login()` actualmente solo emite un JWT de acceso (15 min) y no persiste ni devuelve refresh tokens.
- No existen endpoints `POST /api/auth/refresh` ni `POST /api/auth/logout`.

---

## Cambios Propuestos

### 1. Base de Datos y Migración

#### [NEW] `V12__add_refresh_token_rotation_support.sql`
- Ubicación: `backend/src/main/resources/db/migration/V12__add_refresh_token_rotation_support.sql`
- Agrega la columna `replaced_by UUID REFERENCES identity.refresh_tokens (id)` para enlazar tokens rotados y permitir trazabilidad y detección de reuso malicioso.

---

### 2. Dominio y Repositorio (`com.distribuidora.identity`)

#### [NEW] `RefreshToken.java`
- Ubicación: `backend/src/main/java/com/distribuidora/identity/domain/RefreshToken.java`
- Entidad JPA mapeada a `identity.refresh_tokens`.
- Campos: `id`, `userId`, `tokenHash`, `expiresAt`, `revokedAt`, `replacedBy`, `createdAt`.
- Métodos de negocio:
  - `isExpired(Instant now)`
  - `isRevoked()`
  - `revoke(UUID replacedBy, Instant now)`

#### [NEW] `RefreshTokenRepository.java`
- Ubicación: `backend/src/main/java/com/distribuidora/identity/infrastructure/RefreshTokenRepository.java`
- Métodos:
  - `Optional<RefreshToken> findByTokenHash(String tokenHash)`
  - `@Modifying @Query("update RefreshToken rt set rt.revokedAt = ?2 where rt.userId = ?1 and rt.revokedAt is null") void revokeAllByUserId(UUID userId, Instant revokedAt)`

#### [NEW] `InvalidRefreshTokenException.java`
- Ubicación: `backend/src/main/java/com/distribuidora/identity/application/InvalidRefreshTokenException.java`
- Excepción no verificada para tokens no encontrados, expirados, revocados o reusados.

---

### 3. Servicios y Controladores (`AuthService`, `AuthController`)

#### [MODIFY] `AuthDtos.java`
- `LoginResponse`: agregar campo `String refreshToken`.
- `RefreshRequest`: `record RefreshRequest(@NotBlank String refreshToken)`.
- `LogoutRequest`: `record LogoutRequest(@NotBlank String refreshToken)`.

#### [MODIFY] `AuthService.java`
- Inyectar `RefreshTokenRepository`.
- **Generación y Hash:**
  - Token plano: `SecureRandom` 32 bytes en Base64 URL-safe (~43 caracteres).
  - Hash: SHA-256 en hexadecimal (64 caracteres) para almacenar y buscar en `identity.refresh_tokens`.
  - Duración configurable: 7 días por defecto (`app.security.refresh-token-days: 7`).
- **`login(LoginRequest)`:**
  - Valida credenciales.
  - Emite `accessToken` (JWT).
  - Genera y persiste `RefreshToken`.
  - Retorna `LoginResponse` con `accessToken` y `refreshToken`.
- **`refresh(RefreshRequest)` (Transaccional):**
  - Calcula hash del refresh token recibido y busca la fila con `SELECT ... FOR UPDATE` para serializar renovaciones simultáneas.
  - Si no existe: audita falla `TOKEN_REFRESH (token_not_found)` y lanza `InvalidRefreshTokenException`.
  - Si el token fue reemplazado (`replacedBy != null`), detecta reuso y revoca las sesiones activas.
  - Si fue revocado sin reemplazo (por ejemplo, logout), lo rechaza sin revocar otras sesiones.
  - Si está expirado:
    - Audita falla `TOKEN_REFRESH (token_expired)`.
    - Lanza `InvalidRefreshTokenException("Token de refresco expirado")`.
  - Valida estado del usuario (`ACTIVE`).
  - **Rotación atómica:**
    - Genera nuevo refresh token plano y su hash.
    - Persiste nuevo `RefreshToken`.
    - Marca el token anterior con `revokedAt = now` y `replacedBy = newToken.getId()`.
    - Emite nuevo `accessToken` JWT con permisos actualizados del usuario.
    - Audita `TOKEN_REFRESH` (éxito).
    - Retorna `LoginResponse` con el nuevo par de tokens.
- **`logout(LogoutRequest)`:**
  - Calcula hash y busca el token.
  - Si existe y no está revocado, establece `revokedAt = now`.
  - Audita `LOGOUT` (éxito).

#### [MODIFY] `AuthController.java`
- `POST /api/auth/refresh`: recibe `RefreshPayload`, ejecuta `authService.refresh()` y retorna `LoginResponse` (200 OK).
- `POST /api/auth/logout`: recibe `LogoutPayload`, ejecuta `authService.logout()` y retorna 204 No Content.

---

### 4. Seguridad y Manejo de Errores

#### [MODIFY] `SecurityConfig.java`
- Permitir `/api/auth/refresh` y `/api/auth/logout` en `permitAll()`.

#### [MODIFY] `ApiExceptionHandler.java`
- Manejar `InvalidRefreshTokenException` devolviendo HTTP 401 `INVALID_REFRESH_TOKEN`.

---

## Plan de Verificación

### Tests Automatizados
- [NEW] `RefreshTokenMigrationContractTest.java`: valida script `V12`.
- [MODIFY] `AuthServiceTest.java`:
  - Login exitoso emite access token y refresh token persistido.
  - Refresh exitoso rota el refresh token, revoca el anterior y devuelve nuevo access token.
  - Refresh con token desconocido lanza 401 y audita falla.
  - Refresh con token expirado lanza 401.
  - Detección de reuso: refresh con token ya revocado revoca todas las sesiones del usuario y audita alerta.
  - Logout revoca el refresh token activo.
- [NEW] `AuthControllerTest.java`: verifica endpoints HTTP 200, 401 y 204.
- Regresión completa: `.\mvn.cmd test` (142+ tests en verde).
