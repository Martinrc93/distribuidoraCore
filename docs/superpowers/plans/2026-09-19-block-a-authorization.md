# Block A Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce ADMIN/SELLER permissions and seller ownership across users, customers, orders, payments, lifecycle, and PDFs while securing deployment defaults.

**Architecture:** Seed canonical roles and permissions through Flyway, resolve the current principal through one `CurrentUserAccess` application component, and apply ownership predicates in SQL/services rather than filtering global results in memory. User creation, role assignment, and seller-profile creation are one audited transaction; local demo configuration is separated from secure non-local defaults.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security method authorization, JdbcTemplate/JPA, PostgreSQL 16, Flyway, JUnit 5, Mockito, MockMvc.

## Global Constraints

- Roles are limited to `ADMIN` and `SELLER` in this increment.
- `ADMIN` has `ADMIN_ALL` and global access.
- A seller may access only assigned customers and associated orders/payments.
- A foreign resource returns `404 NOT_FOUND`, not `403`, to avoid disclosing existence.
- `@PreAuthorize` enforces functional permission; SQL/application checks enforce ownership.
- `POST /api/users` accepts an administrator-supplied temporary password.
- Creating a `SELLER` also creates `seller.seller_profiles` atomically; `displayName` is mandatory.
- Product reads needed by sellers must not expose `cost`.
- PDF download follows order ownership but does not add order status or payment-method detail to the PDF.
- Outside explicit local/demo configuration, JWT secret and credentials have no unsafe defaults and demo seed is disabled.
- Do not implement pricing synchronization, cancelled-balance fixes, transaction-lock changes, discount snapshots, CI, or permanent Testcontainers coverage in this plan.

---

### Task 1: Seed canonical roles and permissions

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__seed_roles_and_permissions.sql`
- Create: `backend/src/test/java/com/distribuidora/identity/AuthorizationMigrationContractTest.java`
- Modify: `backend/src/main/java/com/distribuidora/demo/DemoDataSeeder.java`

**Interfaces:**
- Produces database rows `ADMIN`, `SELLER`, `ADMIN_ALL`, `USER_MANAGE`, `ORDER_CREATE`, `SALE_DELIVER`, and required role-permission links.
- Later tasks resolve roles by stable `code`, never by generated IDs.

- [ ] **Step 1: Write the failing migration contract test**

Assert V8 contains idempotent inserts for both roles, canonical permissions, `ADMIN_ALL`/`USER_MANAGE` assignment to `ADMIN`, and order/delivery permissions for `SELLER`.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `mvn -q -Dtest=AuthorizationMigrationContractTest test` from `backend`.
Expected: FAIL because V8 does not exist.

- [ ] **Step 3: Implement V8 and simplify demo role seeding**

Use deterministic SQL lookups by code and `ON CONFLICT DO NOTHING`. Change the demo seeder to reuse canonical rows instead of creating duplicate role/permission definitions.

- [ ] **Step 4: Run focused and demo tests**

Run: `mvn -q -Dtest=AuthorizationMigrationContractTest,DemoDataSeederTest test`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V8__seed_roles_and_permissions.sql backend/src/test/java/com/distribuidora/identity/AuthorizationMigrationContractTest.java backend/src/main/java/com/distribuidora/demo/DemoDataSeeder.java
git commit -m "feat: seed canonical authorization roles"
```

### Task 2: Resolve current admin and seller ownership

**Files:**
- Create: `backend/src/main/java/com/distribuidora/shared/security/CurrentUserAccess.java`
- Create: `backend/src/test/java/com/distribuidora/shared/security/CurrentUserAccessTest.java`

**Interfaces:**
- Produces `UUID userId()`.
- Produces `boolean isAdmin()` based on authority `ADMIN_ALL`.
- Produces `Optional<UUID> sellerProfileId()` for the authenticated user.
- Produces `UUID requireSellerProfile()` for seller-only ownership checks.
- Produces `void requireCustomerAccess(UUID customerId)` and `void requireOrderAccess(UUID orderId)`, with admin bypass and `EmptyResultDataAccessException` for absent/foreign resources.

- [ ] **Step 1: Write failing unit tests**

Cover admin bypass, seller profile resolution, assigned customer access, own order access through `order.seller_id`, legacy access through current customer assignment, and foreign resource returning not-found.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `mvn -q -Dtest=CurrentUserAccessTest test`.
Expected: FAIL because `CurrentUserAccess` does not exist.

- [ ] **Step 3: Implement the access component**

Read the authenticated UUID and authorities from `SecurityContextHolder`. Use `JdbcTemplate` existence queries containing the seller predicate. Do not expose whether a rejected row exists.

- [ ] **Step 4: Run focused tests**

Run: `mvn -q -Dtest=CurrentUserAccessTest test`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/distribuidora/shared/security/CurrentUserAccess.java backend/src/test/java/com/distribuidora/shared/security/CurrentUserAccessTest.java
git commit -m "feat: add seller ownership policy"
```

### Task 3: Add atomic user and role administration

**Files:**
- Create: `backend/src/main/java/com/distribuidora/identity/api/UserAdminController.java`
- Create: `backend/src/main/java/com/distribuidora/identity/api/UserAdminDtos.java`
- Create: `backend/src/main/java/com/distribuidora/identity/application/UserAdminService.java`
- Create: `backend/src/test/java/com/distribuidora/identity/UserAdminServiceTest.java`
- Create: `backend/src/test/java/com/distribuidora/identity/UserAdminControllerTest.java`
- Modify: `backend/src/main/java/com/distribuidora/identity/application/BootstrapAdminRunner.java`
- Modify: `backend/src/test/java/com/distribuidora/identity/AuthServiceTest.java` or create `BootstrapAdminRunnerTest.java`

**Interfaces:**
- `POST /api/users` body: `email`, `temporaryPassword`, `role`, nullable `displayName`.
- `UserAdminService.create(CreateUserRequest): UUID`.
- Only `USER_MANAGE` or `ADMIN_ALL` may call the endpoint.

- [ ] **Step 1: Write failing service tests**

Cover ADMIN user plus role without seller profile, SELLER user plus role/profile, required seller display name, invalid role, duplicate email conflict, Argon2 encoding, audit event, and rollback-oriented ordering inside one transaction.

- [ ] **Step 2: Run service tests and verify failure**

Run: `mvn -q -Dtest=UserAdminServiceTest test`.
Expected: FAIL because the service does not exist.

- [ ] **Step 3: Implement DTO and transactional service**

Normalize email with `Locale.ROOT`, validate only `ADMIN|SELLER`, encode the temporary password, save the user, insert `identity.user_roles`, insert seller profile when required, and audit within the transaction.

- [ ] **Step 4: Write and run failing controller tests**

Cover admin success `201`, returned UUID, unauthenticated `401`, seller/insufficient permission `403`, invalid payload `400`, and duplicate email `409`.

- [ ] **Step 5: Implement controller and exception mapping**

Use `@PreAuthorize("hasAnyAuthority('USER_MANAGE','ADMIN_ALL')")` and existing ProblemDetail conventions.

- [ ] **Step 6: Fix bootstrap admin role assignment**

Make bootstrap creation/repair idempotently assign the `ADMIN` role whether the configured user is newly created or already exists. Add tests proving resulting authorities include `ADMIN_ALL`.

- [ ] **Step 7: Run focused and full identity tests**

Run: `mvn -q -Dtest=UserAdminServiceTest,UserAdminControllerTest,BootstrapAdminRunnerTest,AuthServiceTest test`.
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/distribuidora/identity backend/src/test/java/com/distribuidora/identity
git commit -m "feat: add role-aware user administration"
```

### Task 4: Restrict commands and capture seller ownership

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/customer/api/CustomerCommandController.java`
- Modify: `backend/src/main/java/com/distribuidora/catalog/api/ProductCommandController.java`
- Modify: `backend/src/main/java/com/distribuidora/order/api/OrderConfirmationController.java`
- Modify: `backend/src/main/java/com/distribuidora/order/application/OrderConfirmationService.java`
- Modify/Create tests under `backend/src/test/java/com/distribuidora/customer`, `catalog`, and `order`.

**Interfaces:**
- Customer/product mutations require `ADMIN_ALL`.
- Confirmation accepts `ORDER_CREATE` or `ADMIN_ALL`.
- Confirmation calls `CurrentUserAccess.requireCustomerAccess(customerId)`.
- Confirmation persists `orders.seller_id`: seller caller uses own profile; admin uses the customer’s assigned seller.

- [ ] **Step 1: Write failing authorization tests**

Assert customer/product create/update/status methods reject seller authority and permit admin authority. Assert confirmation permits seller/admin but rejects a seller’s foreign customer.

- [ ] **Step 2: Run focused tests and verify failure**

Run the customer, product, and order controller/service test classes.
Expected: FAIL because current command annotations and ownership behavior are too broad.

- [ ] **Step 3: Add command permissions and confirmation ownership**

Apply `ADMIN_ALL` to customer/product mutations. Inject `CurrentUserAccess` into confirmation, validate customer ownership before pricing/inventory, resolve one seller ID, and include `seller_id` in the order insert.

- [ ] **Step 4: Add seller snapshot tests**

Assert seller callers persist their own profile, admin callers preserve the customer-assigned profile, and all preexisting confirmation/idempotency tests still pass.

- [ ] **Step 5: Run focused tests**

Run: `mvn -q -Dtest=CustomerCommandServiceTest,ProductCommandServiceTest,OrderConfirmationServiceTest test` plus new controller tests.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/distribuidora/customer backend/src/main/java/com/distribuidora/catalog backend/src/main/java/com/distribuidora/order backend/src/test/java/com/distribuidora/customer backend/src/test/java/com/distribuidora/catalog backend/src/test/java/com/distribuidora/order
git commit -m "fix: enforce command ownership and permissions"
```

### Task 5: Scope seller read APIs

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/dashboard/api/ReadQueryController.java`
- Modify: `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java`
- Modify/Create: `backend/src/test/java/com/distribuidora/dashboard/ReadQueryServiceTest.java`
- Create: `backend/src/test/java/com/distribuidora/dashboard/ReadQueryControllerTest.java`

**Interfaces:**
- Admin retains global dashboard/users/inventory/movements/customer/product/order/sale/payment reads.
- Seller receives only assigned customers, products without `cost`, owned orders/sales, and payments attached to owned orders.
- Paged data and count queries apply identical ownership predicates.

- [ ] **Step 1: Write failing scoped-query tests**

Cover own/foreign customers, orders by ID/number, sales, and payments; assert seller product projection omits `cost`; assert user/inventory/global dashboard endpoints require admin.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `mvn -q -Dtest=ReadQueryServiceTest,ReadQueryControllerTest test`.
Expected: FAIL because reads are currently global.

- [ ] **Step 3: Implement SQL ownership predicates**

Inject `CurrentUserAccess`. Branch once per request between admin query and seller query. Add `seller_id` predicates to both select and count SQL. Do not retrieve global rows and filter in Java.

- [ ] **Step 4: Restrict admin-only endpoints**

Apply `ADMIN_ALL` to users, inventory movements, and global administrative dashboard data. Provide seller-safe customer/product/order/payment endpoints under the existing routes.

- [ ] **Step 5: Run focused tests**

Run: `mvn -q -Dtest=ReadQueryServiceTest,ReadQueryControllerTest test`.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/distribuidora/dashboard backend/src/test/java/com/distribuidora/dashboard
git commit -m "fix: scope seller read access"
```

### Task 6: Protect lifecycle and PDF resources

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/order/application/DeliveryLifecycleService.java`
- Modify: `backend/src/main/java/com/distribuidora/document/application/SaleDocumentService.java`
- Modify tests: `backend/src/test/java/com/distribuidora/order/DeliveryLifecycleServiceTest.java`
- Modify tests: `backend/src/test/java/com/distribuidora/document/SaleDocumentServiceTest.java`
- Modify tests: `backend/src/test/java/com/distribuidora/document/DocumentControllerTest.java`

**Interfaces:**
- `recordAttempt` requires owned-order access for seller and global access for admin.
- Cancellation remains admin-only.
- PDF loading requires owned-order access before returning model bytes.
- PDF content remains unchanged: no order status and no payment-method table.

- [ ] **Step 1: Write failing lifecycle/PDF ownership tests**

Assert own seller succeeds, foreign seller receives not-found, admin succeeds, and cancellation remains admin-only. Assert no PDF bytes/model are produced after access rejection.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `mvn -q -Dtest=DeliveryLifecycleServiceTest,SaleDocumentServiceTest,DocumentControllerTest test`.
Expected: FAIL because ownership is not currently checked.

- [ ] **Step 3: Apply shared ownership checks**

Inject `CurrentUserAccess`, call `requireOrderAccess` before lifecycle mutation and document data queries, and preserve all existing transaction/read-only behavior.

- [ ] **Step 4: Run focused tests**

Run the same focused command.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/distribuidora/order/application/DeliveryLifecycleService.java backend/src/main/java/com/distribuidora/document/application/SaleDocumentService.java backend/src/test/java/com/distribuidora/order/DeliveryLifecycleServiceTest.java backend/src/test/java/com/distribuidora/document
git commit -m "fix: protect order lifecycle and documents"
```

### Task 7: Secure configuration, documentation, and end-to-end verification

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-local.yml`
- Modify: `compose.yaml`
- Modify: `backend/README.md`
- Modify: `docs/development/backend-checklist.md`
- Create/Modify tests validating security configuration startup.
- Modify: `scripts/order-confirmation-smoke.ps1` to verify seller ownership if feasible without weakening existing smoke coverage.

**Interfaces:**
- Non-local startup requires explicit `JWT_SECRET`.
- Compose activates `local`, where demo defaults are explicitly documented as non-production.
- Demo seed remains disabled unless local configuration explicitly enables it.

- [ ] **Step 1: Write failing configuration tests**

Assert default/non-local configuration has no fallback JWT secret or demo password and seed demo is false. Assert local profile supplies documented development defaults.

- [ ] **Step 2: Run focused configuration tests and verify failure**

Run the new configuration test class.
Expected: FAIL because unsafe defaults currently live in base configuration.

- [ ] **Step 3: Split local and secure defaults**

Move known local values to `application-local.yml`; require environment values in base configuration; activate `SPRING_PROFILES_ACTIVE=local` in Compose; add a clear non-production warning.

- [ ] **Step 4: Update API and checklist documentation**

Document user creation, ADMIN/SELLER access matrix, seller ownership rules, PDF ownership without status/payment-method display, secure deployment variables, and completed Block A items. Correct the stale V7 checklist entries while leaving full frontend printing pending.

- [ ] **Step 5: Run complete verification**

Run:

```powershell
cd backend; mvn test; mvn package -DskipTests
cd ..\frontend; npm test -- --run; npm run build
cd ..; docker compose config
```

Expected: all tests/builds pass and Compose resolves the local profile.

- [ ] **Step 6: Run PostgreSQL smoke**

Rebuild the Compose backend without deleting the persistent volume. Verify admin global access, seller own-resource access, seller foreign-resource `404`, user-role creation, order confirmation, lifecycle, and PDF smoke invariants.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources backend/README.md compose.yaml docs/development/backend-checklist.md scripts/order-confirmation-smoke.ps1 backend/src/test
git commit -m "docs: complete authorization remediation block"
```
