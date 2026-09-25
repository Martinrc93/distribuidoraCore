# Inventory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar ajustes de stock transaccionales, auditables y protegidos por permiso, junto con el historial de movimientos y sus consultas paginadas.

**Architecture:** Se seguirá el patrón existente de commands con `JdbcTemplate`. `InventoryCommandService.adjust` actualizará el balance bloqueado con `SELECT ... FOR UPDATE` e insertará el movimiento en la misma transacción; `AuditService` registrará el evento de forma independiente. Las consultas permanecerán en `ReadQueryService` para reutilizar `PageResponse`.

**Tech Stack:** Spring Boot 3.5.16, Java 21, Spring JDBC, Spring Security method security, PostgreSQL 16, Flyway, JUnit 5, Mockito, Docker Compose.

> Estado actualizado (2026-09-24): el alcance de abajo es el plan inicial. Los
> movimientos de venta/devolución/cancelación y el soporte multi-depósito se
> completaron después; V23 y el contrato vigente están en
> [`docs/api/inventory.md`](../../api/inventory.md).

## Global Constraints

- El stock negativo está permitido.
- Las cantidades son decimales en múltiplos de `0.5`.
- Los movimientos nunca se editan ni eliminan.
- Solo usuarios con autoridad `STOCK_ADJUST` pueden ajustar stock.
- Todos los ajustes requieren JWT y auditoría append-only.
- El endpoint de ajuste recibe un delta firmado; positivo suma y negativo resta.
- El motivo del ajuste es obligatorio.
- Alcance inicial del plan (2026-09-18): no incluía movimientos automáticos por ventas ni múltiples depósitos; ambos se completaron después (V23 para multi-depósito).

---

### Task 1: Persistencia y autoridad de inventario

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__harden_inventory_movements.sql`
- Modify: `backend/src/main/java/com/distribuidora/shared/security/JwtService.java`
- Test: `backend/src/test/java/com/distribuidora/shared/security/JwtServiceTest.java` (create if absent)

**Interfaces:**
- Produces the database guarantees and JWT authority consumed by Tasks 2 and 3.

- [ ] **Step 1: Inspect existing migration and JWT tests**

Run:

```powershell
Get-ChildItem backend/src/test/java -Recurse -Filter *Jwt*Test.java
```

Use the existing test style if the file exists; otherwise create a focused unit test for the authority claim.

- [ ] **Step 2: Write the failing authority test**

Assert that an administrator token contains both `ADMIN_ALL` and `STOCK_ADJUST`, while a non-administrator token does not contain `STOCK_ADJUST`.

- [ ] **Step 3: Add the migration constraints and index**

Create `V4__harden_inventory_movements.sql` with:

```sql
ALTER TABLE inventory.stock_movements
    ADD CONSTRAINT ck_stock_movement_type
    CHECK (movement_type IN ('SALE', 'SALE_CANCELLATION', 'MANUAL_ENTRY', 'MANUAL_ADJUSTMENT', 'RETURN'));

ALTER TABLE inventory.stock_movements
    ADD CONSTRAINT ck_stock_movement_quantity
    CHECK (quantity <> 0 AND mod(quantity * 2, 1) = 0);

CREATE INDEX ix_stock_movements_product_created_at
    ON inventory.stock_movements (product_id, created_at DESC);

ALTER TABLE inventory.stock_movements
    ADD COLUMN reason VARCHAR(500) NOT NULL DEFAULT 'Migración inicial';

ALTER TABLE inventory.stock_movements
    ALTER COLUMN reason DROP DEFAULT;
```

- [ ] **Step 4: Add `STOCK_ADJUST` to administrator JWT claims**

Keep the current claim behavior and change only the administrator branch to include `STOCK_ADJUST` alongside `ROLE_USER` and `ADMIN_ALL`.

- [ ] **Step 5: Run the focused tests and migration validation**

Run:

```powershell
mvn -q "-Dtest=JwtServiceTest" test
mvn -q -DskipTests package
```

Expected: PASS and Flyway includes V4 during application startup.

### Task 2: Transactional inventory command

**Files:**
- Create: `backend/src/main/java/com/distribuidora/inventory/application/InventoryCommandService.java`
- Create: `backend/src/main/java/com/distribuidora/inventory/api/InventoryCommandController.java`
- Test: `backend/src/test/java/com/distribuidora/inventory/InventoryCommandServiceTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate`, `AuditService`, authenticated JWT authority.
- Produces: `void adjust(UUID productId, BigDecimal quantity, String reason)` and `POST /api/inventory/{productId}/adjustments` with `204` response.

- [ ] **Step 1: Write validation tests first**

Cover these exact inputs:

```java
new BigDecimal("0")       // reject
new BigDecimal("0.25")    // reject
new BigDecimal("-0.25")   // reject
new BigDecimal("-1.5")    // accept
new BigDecimal("2.0")     // accept
```

Also reject `null`, blank reason, and reason longer than `500` characters.

- [ ] **Step 2: Write the transaction interaction test**

Mock `JdbcTemplate` so the product is active and the balance query returns `10.0`. Verify the service executes:

```sql
select quantity from inventory.inventory_balances where product_id = ? for update
update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?
insert into inventory.stock_movements(... movement_type, quantity, reason, ...) values (..., 'MANUAL_ADJUSTMENT', ?, ...)
```

Verify `AuditService.record` receives operation `STOCK_ADJUSTMENT`, resource type `PRODUCT`, and the signed delta in details.

- [ ] **Step 3: Implement `InventoryCommandService`**

Use `@Transactional`. Validate before SQL, verify product status with `select status from catalog.products where id = ?`, lock the balance with `FOR UPDATE`, calculate `current + delta`, update `updated_at`, insert a UUID movement with `MANUAL_ADJUSTMENT` and the validated reason, and call `AuditService.record` using the authenticated actor UUID.

Throw `IllegalArgumentException` for invalid input, `EmptyResultDataAccessException` for missing product/balance, and `IllegalStateException` when the product is inactive.

- [ ] **Step 4: Implement the request DTO and controller**

Use:

```java
public record AdjustmentRequest(@NotNull BigDecimal quantity, @NotBlank @Size(max = 500) String reason) {}
```

Map `POST /api/inventory/{productId}/adjustments` to `service.adjust(...)`, annotate it with `@PreAuthorize("hasAuthority('STOCK_ADJUST')")`, and return `ResponseEntity.noContent()`.

- [ ] **Step 5: Run command tests**

Run:

```powershell
mvn -q "-Dtest=InventoryCommandServiceTest" test
```

Expected: PASS.

### Task 3: Movement query API

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java`
- Modify: `backend/src/main/java/com/distribuidora/dashboard/api/ReadQueryController.java`
- Test: `backend/src/test/java/com/distribuidora/dashboard/ReadQueryServiceTest.java` (create if absent)

**Interfaces:**
- Consumes: existing `PageResponse` pagination helper and `JdbcTemplate`.
- Produces: `PageResponse<Map<String,Object>> movements(UUID productId, int page, int size)` and `GET /api/inventory/{productId}/movements`.

- [ ] **Step 1: Write the query test**

Assert that the query filters by `product_id`, orders by `created_at DESC`, includes `movement_type`, `quantity`, `reference_type`, `reference_id`, and returns `PageResponse` metadata.

- [ ] **Step 2: Implement the service query**

Add a method using the existing private `page(...)` helper:

```sql
select sm.id, sm.movement_type as "movementType", sm.quantity, sm.reason,
       sm.reference_type as "referenceType", sm.reference_id as "referenceId",
       sm.created_at as date
from inventory.stock_movements sm
where sm.product_id = ?
order by sm.created_at desc
```

Use a matching `count(*)` query and parameters in the same order.

- [ ] **Step 3: Add the controller route**

Add `@GetMapping("/inventory/{productId}/movements")` with defaults `page=0` and `size=20`. Keep it authenticated through the existing global security rule.

- [ ] **Step 4: Run query tests and full backend tests**

Run:

```powershell
mvn test
```

Expected: PASS.

### Task 4: Integration verification and documentation

**Files:**
- Modify: `docs/development/backend-implementation-roadmap.md`
- Modify: `backend/README.md`
- Test: Docker Compose PostgreSQL runtime

**Interfaces:**
- Consumes: Tasks 1-3 endpoints and migration V4.
- Produces: documented inventory endpoints and verified runtime behavior.

- [ ] **Step 1: Update the roadmap**

Mark the implemented inventory items as partial: balances, append-only movements, manual adjustment, pessimistic locking, and audit. Leave sales/cancellation deltas explicitly pending.

- [ ] **Step 2: Document API examples**

Add authenticated examples for positive and negative adjustments plus movement history to `backend/README.md`, including expected `204`, `400`, and `403` responses.

- [ ] **Step 3: Rebuild and start Compose**

Run:

```powershell
docker compose up -d --build
```

Wait for the backend health check to report `UP`.

- [ ] **Step 4: Execute the real PostgreSQL smoke test**

Login as `admin1@distribuidora.local`, create an adjustment of `2.5`, query `/api/inventory`, query product movements, and verify balance increased by `2.5`. Repeat with a non-admin token and verify `403`.

- [ ] **Step 5: Run final verification**

Run:

```powershell
mvn test
mvn package -DskipTests
docker compose config
docker compose ps
```

Expected: all tests pass, Compose configuration is valid, database is healthy, backend health is `UP`, and frontend remains reachable.
