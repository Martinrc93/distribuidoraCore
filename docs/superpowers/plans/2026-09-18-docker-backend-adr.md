# Docker, Database Connection, and Backend ADR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ejecutar frontend, backend y PostgreSQL juntos mediante Docker Compose y convertir los ADR iniciales en una primera vertical backend verificable.

**Architecture:** Se mantiene el monolito modular Spring Boot con un schema PostgreSQL por módulo y Flyway como único dueño de la evolución del esquema. Docker Compose orquesta PostgreSQL, backend y un Nginx que sirve la SPA y proxifica `/api` y `/actuator` al backend. La primera vertical implementa identidad local, JWT, permisos básicos, correlación de requests y auditoría append-only; los módulos comerciales se incorporan por fases posteriores sin saltar sus límites.

**Tech Stack:** Docker Compose, PostgreSQL 16, Spring Boot 3.5.16, Java 21, Spring Security, JPA, Flyway, React 19, Vite, Nginx.

## Global Constraints

- PostgreSQL es la persistencia principal y Flyway es el mecanismo exclusivo de migraciones.
- Hibernate mantiene `ddl-auto=validate`.
- La aplicación conserva un monolito modular y no comparte entidades JPA entre módulos.
- Los secrets y credenciales se reciben por variables de entorno; no se agregan credenciales reales al repositorio.
- Los tests se ejecutan antes de declarar cada etapa terminada.

---

### Task 1: Docker Compose de desarrollo

**Files:**
- Create: `compose.yaml`
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `frontend/nginx.conf`
- Create: `.dockerignore`
- Modify: `frontend/vite.config.ts`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `scripts/README.md`

**Interfaces:**
- Consumes: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT` en Spring Boot.
- Produces: servicios `db`, `backend` y `frontend`; frontend disponible en `http://localhost:3000`, API en `http://localhost:8080` y PostgreSQL en `localhost:5432`.

- [ ] **Step 1: Write the Compose file** con PostgreSQL 16, volumen nombrado, healthcheck `pg_isready`, backend dependiente de `service_healthy` y frontend dependiente del backend.
- [ ] **Step 2: Write backend and frontend images** con build reproducible: Maven builder + JRE runtime para backend; Node builder + Nginx runtime para frontend.
- [ ] **Step 3: Configure Nginx and Vite proxy** para que `/api/` y `/actuator/` lleguen al backend y el desarrollo local use el mismo prefijo.
- [ ] **Step 4: Document startup and lifecycle commands**: `docker compose up --build`, `docker compose down`, `docker compose down -v`.
- [ ] **Step 5: Verify** con `docker compose config` y, si Docker está disponible, `docker compose up --build -d` seguido de los health endpoints.

### Task 2: Persistencia real y primera migración de identidad/auditoría

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__create_identity_and_audit_tables.sql`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application-test.yml`
- Modify: `backend/src/test/java/com/distribuidora/DistribuidoraApplicationTest.java`

**Interfaces:**
- Consumes: schemas creados por `V1__create_module_schemas.sql`.
- Produces: tablas `identity.users`, `identity.roles`, `identity.permissions`, `identity.user_roles`, `identity.role_permissions`, `identity.refresh_tokens` y `audit.audit_events`.

- [ ] **Step 1: Write migration verification test** que arranque con PostgreSQL de Compose o Testcontainers y compruebe que Flyway crea schemas y tablas.
- [ ] **Step 2: Run the test to verify it fails** porque aún no existen las tablas V2.
- [ ] **Step 3: Add V2 migration** con UUID, estados, timestamps UTC, constraints, índices, hashes de refresh token y auditoría append-only.
- [ ] **Step 4: Run backend tests** y verificar que `ddl-auto=validate` no intenta crear tablas.

### Task 3: Autenticación local JWT y permisos

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/distribuidora/identity/domain/UserStatus.java`
- Create: `backend/src/main/java/com/distribuidora/identity/domain/UserAccount.java`
- Create: `backend/src/main/java/com/distribuidora/identity/infrastructure/UserAccountRepository.java`
- Create: `backend/src/main/java/com/distribuidora/identity/application/AuthService.java`
- Create: `backend/src/main/java/com/distribuidora/identity/api/AuthController.java`
- Create: `backend/src/main/java/com/distribuidora/identity/api/AuthDtos.java`
- Create: `backend/src/main/java/com/distribuidora/shared/security/JwtService.java`
- Modify: `backend/src/main/java/com/distribuidora/shared/security/SecurityConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/distribuidora/identity/AuthControllerTest.java`

**Interfaces:**
- Consumes: `identity.users` y el `PasswordEncoder` Argon2 existente.
- Produces: `POST /api/auth/login` con access token de 15 minutos; endpoints protegidos mediante Bearer JWT; permiso `ADMIN_ALL` como autoridad global.

- [ ] **Step 1: Write controller tests** para login válido, password inválido y acceso de un endpoint protegido con Bearer token.
- [ ] **Step 2: Run tests to verify they fail** porque no existe servicio JWT ni endpoint de autenticación.
- [ ] **Step 3: Add JWT dependencies and configuration** con secret configurable, expiración configurable y sesión stateless.
- [ ] **Step 4: Implement the identity aggregate and login service**; nunca devolver ni persistir passwords en claro.
- [ ] **Step 5: Replace HTTP Basic** por resource server Bearer JWT y permitir solo health, OpenAPI y login sin autenticación.
- [ ] **Step 6: Run tests and package** con `mvn test` y `mvn package -DskipTests`.

### Task 4: Correlación y auditoría de operaciones de seguridad

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/shared/web/RequestIdFilter.java`
- Create: `backend/src/main/java/com/distribuidora/audit/domain/AuditEvent.java`
- Create: `backend/src/main/java/com/distribuidora/audit/infrastructure/AuditEventRepository.java`
- Create: `backend/src/main/java/com/distribuidora/audit/application/AuditService.java`
- Modify: `backend/src/main/java/com/distribuidora/identity/application/AuthService.java`
- Create: `backend/src/test/java/com/distribuidora/audit/AuditEventTest.java`

**Interfaces:**
- Consumes: header `X-Request-Id` opcional y contexto autenticado de Spring Security.
- Produces: response header `X-Request-Id`; evento inmutable para login exitoso y fallido con actor, operación, recurso, resultado y correlation ID.

- [ ] **Step 1: Write audit tests** para conservar el correlation ID y registrar resultado de login.
- [ ] **Step 2: Run tests to verify they fail** sin repository ni servicio de auditoría.
- [ ] **Step 3: Implement audit persistence** sin update/delete públicos.
- [ ] **Step 4: Integrate login audit events** y verify with `mvn test`.

### Task 5: Roadmap ejecutable de los ADR restantes

**Files:**
- Create: `docs/development/backend-implementation-roadmap.md`
- Modify: `backend/README.md`

**Interfaces:**
- Consumes: ADR-001 a ADR-009.
- Produces: fases ordenadas, dependencias, migraciones esperadas, endpoints y criterios de aceptación para seller/customer/catalog/inventory/order/sale/payment/document/notification.

- [ ] **Step 1: Document phase boundaries**: identity, seller/customer, catalog/pricing, inventory, order/sale/payment, documents/notifications, audit/operations.
- [ ] **Step 2: Document acceptance checks** por fase incluyendo invariantes de stock, estados de pedido, pagos y auditoría.
- [ ] **Step 3: Update backend README** con la ejecución Docker y el orden de implementación.
- [ ] **Step 4: Verify documentation links** y comandos contra la estructura real.
