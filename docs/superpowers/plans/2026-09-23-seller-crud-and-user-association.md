# Implementation Plan: CRUD Completo de Vendedores y Asociación con Usuarios

- **Date:** 2026-09-23
- **Branch:** `feature/backend`
- **Goal:** Implementar el CRUD administrativo completo de perfiles de vendedor (`seller.seller_profiles`), asociación con usuarios existentes (`identity.users`), ciclo de vida (`ACTIVE`/`INACTIVE`), consultas detalladas y filtradas, y endpoints protegidos con `ADMIN_ALL`.

---

## 1. Contexto y Requisitos

- `seller.seller_profiles` fue creada en `V3__create_commercial_tables.sql`:
  - `id UUID PRIMARY KEY`
  - `user_id UUID NOT NULL UNIQUE REFERENCES identity.users (id)`
  - `display_name VARCHAR(160) NOT NULL`
  - `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE'))`
  - `created_at TIMESTAMPTZ NOT NULL`
- `CurrentUserAccess` ya valida `status = 'ACTIVE'` para resolver el vendedor activo y restringir el acceso a clientes y órdenes.
- Falta:
  1. Entidad y repositorio para `seller.seller_profiles`.
  2. Endpoint y servicio para dar de alta perfiles de vendedor para usuarios existentes (`POST /api/sellers`).
  3. Modificación del perfil (`PUT /api/sellers/{id}`).
  4. Cambio de estado / baja lógica (`PATCH /api/sellers/{id}/status`, `POST /api/sellers/{id}/activate`, `POST /api/sellers/{id}/deactivate`, `DELETE /api/sellers/{id}`).
  5. Consulta detallada de vendedor (`GET /api/sellers/{id}`) con datos del usuario (`email`) y conteo de clientes asignados.
  6. Consulta paginada con filtros por búsqueda y estado (`GET /api/sellers?search=...&status=...`).
  7. Métodos internos de consulta / resolución de perfiles de vendedor.
  8. Auditoría append-only (`SELLER_CREATE`, `SELLER_UPDATE`, `SELLER_STATUS`).

---

## 2. Pasos de Implementación (TDD)

### Paso 1: Pruebas Unitarias de Servicio (RED)
- Crear `SellerCommandServiceTest.java` en `com.distribuidora.seller`.
- Casos de prueba:
  - `create`: falla con nombre vacío, usuario inexistente, usuario bloqueado, o usuario que ya tiene perfil.
  - `create`: asigna rol `SELLER` si no lo tiene, persiste perfil y audita `SELLER_CREATE`.
  - `update`: falla con nombre vacío o vendedor inexistente, actualiza nombre y audita `SELLER_UPDATE`.
  - `setStatus` / `activate` / `deactivate`: valida estado (`ACTIVE`/`INACTIVE`), falla si no existe, actualiza y audita `SELLER_STATUS`.
  - `findSellerByUserId` / `findSellerById`: resuelve perfil correctamente.

### Paso 2: Pruebas Unitarias de Controladores (RED)
- Crear `SellerCommandControllerTest.java` en `com.distribuidora.seller`.
- Casos de prueba:
  - Verificación de anotación `@PreAuthorize("hasAuthority('ADMIN_ALL')")` en cada endpoint.
  - `POST /api/sellers` -> 201 Created con ID.
  - `PUT /api/sellers/{id}` -> 204 No Content.
  - `PATCH /api/sellers/{id}/status` -> 204 No Content.
  - `POST /api/sellers/{id}/activate` -> 204 No Content.
  - `POST /api/sellers/{id}/deactivate` -> 204 No Content.
  - `DELETE /api/sellers/{id}` -> 204 No Content.
- Crear/Extender pruebas de consulta en `SellerReadQueryTest.java` para `GET /api/sellers/{id}` y `GET /api/sellers?search=...&status=...`.

### Paso 3: Implementación de Código Productivo (GREEN)
- Entidad `SellerProfile.java` en `com.distribuidora.seller.domain`.
- Repositorio `SellerProfileRepository.java` en `com.distribuidora.seller.infrastructure`.
- DTOs `SellerDtos.java` en `com.distribuidora.seller.api`.
- Servicio `SellerCommandService.java` en `com.distribuidora.seller.application`.
- Controlador `SellerCommandController.java` en `com.distribuidora.seller.api`.
- Métodos `sellers(page, size, search, status)` y `sellerDetail(id)` en `ReadQueryService.java` y `ReadQueryController.java`.

### Paso 4: Verificación Integral (REFACTOR)
- Ejecutar suite completa con `./mvnw test`.
- Confirmar 0 fallos, 0 errores.

### Paso 5: Documentación, Commit y Push
- Actualizar `docs/development/implementation-checklist.md` y `docs/development/backend-checklist.md`.
- Actualizar `walkthrough.md`.
- Commit: `feat(seller): implement full seller profile CRUD and user association`.
- Push a `feature/backend`.
