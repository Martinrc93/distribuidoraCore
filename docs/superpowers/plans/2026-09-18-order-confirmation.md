# Atomic Order Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar `POST /api/orders/confirm` como una transacción atómica e idempotente que integre Pricing, Inventory, Order, Sale, Payment y cuenta corriente.

**Architecture:** Se usará `JdbcTemplate` con un `OrderConfirmationService` transaccional como orquestador. La lógica pura de importes quedará en un calculador testeable; el movimiento de stock se extraerá a un servicio de inventario reutilizable con locking pesimista. Pricing.resolve será la fuente de precios y las tablas de pedido/venta conservarán snapshots.

**Tech Stack:** Spring Boot 3.5.16, Java 21, Spring JDBC, Spring Security method security, PostgreSQL 16, Flyway, JUnit 5, Mockito, Docker Compose.

## Global Constraints

- No se persisten borradores.
- La confirmación crea `Order CONFIRMED` y `Sale` en una única transacción.
- Las cantidades son positivas y múltiplos de `0.5`.
- Los descuentos son porcentajes entre `0` y `100`, con cuatro decimales como máximo.
- Los importes usan `NUMERIC(19,4)` y `RoundingMode.HALF_UP`.
- `ORDER_CREATE` permite confirmar sin overrides.
- `ADMIN_ALL` habilita overrides de precio/descuento.
- Los movimientos de venta son `SALE` y usan locking pesimista.
- Los pagos monetarios son `CASH` y `BANK_TRANSFER`.
- `CUSTOMER_ACCOUNT` genera débito append-only en cuenta corriente.
- Los pagos no pueden superar el total.
- `idempotencyKey` es obligatorio, máximo 100 caracteres y único.
- Una clave reutilizada con otro payload devuelve `409 IDEMPOTENCY_CONFLICT`.
- No se implementan todavía entrega, cancelación, edición, outbox ni documentos.

---

### Task 1: Migration V6 and legacy compatibility

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__prepare_order_confirmation.sql`
- Test: `backend/src/test/java/com/distribuidora/order/OrderConfirmationMigrationContractTest.java`

**Interfaces:**
- Produces the schema consumed by Tasks 2-5: idempotency, snapshots, and account ledger.

- [ ] **Step 1: Write the migration contract test**

Read the V6 resource and assert it contains:

```text
orders.orders.idempotency_key with a unique constraint/index
price_list_id and price_list_code on order_items and sale_items
line_discount_percent on order_items and sale_items
customer.account_ledger with DEBIT/CREDIT constraint and positive amount
legacy-safe defaults before NOT NULL constraints
```

- [ ] **Step 2: Add idempotency to orders**

Add nullable `idempotency_key VARCHAR(100)` to `orders.orders`, preserving seeded rows, and create a unique index that permits multiple nulls but rejects duplicate non-null keys.

- [ ] **Step 3: Add snapshot fields**

Add to both `orders.order_items` and `sale.sale_items`:

```sql
price_list_id UUID NULL,
price_list_code VARCHAR(40) NOT NULL DEFAULT 'GENERAL',
line_discount_percent NUMERIC(19,4) NOT NULL DEFAULT 0
```

Use the default while migrating existing demo rows, then drop the default so new writes must provide the snapshot values.

- [ ] **Step 4: Create the account ledger**

Create `customer.account_ledger` with UUID id, customer/sale foreign keys, `entry_type` limited to `DEBIT` and `CREDIT`, positive `NUMERIC(19,4)` amount, created timestamp, and an index by customer and created time.

- [ ] **Step 5: Run migration verification**

Run:

```powershell
mvn test
mvn -q -DskipTests package
```

Expected: V6 applies to the existing Compose database without breaking the demo seed.

### Task 2: Order request and amount calculation

**Files:**
- Create: `backend/src/main/java/com/distribuidora/order/application/OrderCalculationService.java`
- Create: `backend/src/main/java/com/distribuidora/order/api/OrderConfirmationDtos.java`
- Create: `backend/src/test/java/com/distribuidora/order/OrderCalculationServiceTest.java`

**Interfaces:**
- Produces `OrderCalculationService.calculate(List<CalculatedLine>, BigDecimal orderDiscountPercent)` returning `OrderCalculation`.
- Produces request records for controller/service: `ConfirmationRequest`, `LineRequest`, and `PaymentRequest`.

- [ ] **Step 1: Write failing calculation tests**

Cover:

```text
quantity=2.0, price=10.0000, line discount=10% -> line total=18.0000
two lines plus 5% order discount -> rounded total at scale 4
zero/negative quantity -> 400 validation
discount below 0 or above 100 -> 400 validation
payment sum greater than total -> conflict validation
```

- [ ] **Step 2: Add explicit request records and validation**

Define:

```java
record ConfirmationRequest(
    @NotBlank @Size(max = 100) String idempotencyKey,
    @NotNull UUID customerId,
    UUID priceListId,
    @NotNull @Size(min = 1) List<LineRequest> lines,
    @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal orderDiscountPercent,
    List<PaymentRequest> payments
) {}
record LineRequest(UUID productId, BigDecimal quantity,
                   BigDecimal lineDiscountPercent, BigDecimal unitPriceOverride) {}
record PaymentRequest(String method, BigDecimal amount) {}
```

Validate line-specific values in the service so the same rules apply outside HTTP.

- [ ] **Step 3: Implement pure BigDecimal calculation**

Calculate `base`, line total, subtotal, line discount, order discount, total, paid and balance using scale 4 and `RoundingMode.HALF_UP`. Reject negative final totals and payment sums over total.

- [ ] **Step 4: Run calculation tests**

Run:

```powershell
mvn -q "-Dtest=OrderCalculationServiceTest" test
```

Expected: PASS.

### Task 3: Reusable transactional sale stock movements

**Files:**
- Create: `backend/src/main/java/com/distribuidora/inventory/application/InventoryMovementService.java`
- Modify: `backend/src/main/java/com/distribuidora/inventory/application/InventoryCommandService.java`
- Create: `backend/src/test/java/com/distribuidora/inventory/InventoryMovementServiceTest.java`

**Interfaces:**
- Produces `void apply(UUID productId, BigDecimal delta, String movementType, UUID referenceId, String reason)` for internal transactional callers.
- `InventoryCommandService` delegates manual adjustments to the reusable service while preserving `MANUAL_ADJUSTMENT` and `STOCK_ADJUST` behavior.

- [ ] **Step 1: Write failing movement tests**

Verify a `SALE` movement locks the balance with `FOR UPDATE`, updates it by a negative delta, inserts the reference and reason, permits negative resulting balances, and rejects unsupported movement types or invalid multiples.

- [ ] **Step 2: Implement the shared movement service**

Use `@Transactional`, select product/balance status, lock the balance row, update quantity, and insert the append-only movement. Permit `SALE` and `SALE_CANCELLATION` for internal callers; keep manual adjustment authorization in `InventoryCommandService`.

- [ ] **Step 3: Refactor manual adjustment to delegate**

Preserve the public `adjust` interface and its audit/permission checks. Delegate only the balance/movement mutation to `InventoryMovementService`.

- [ ] **Step 4: Run inventory tests**

Run:

```powershell
mvn -q "-Dtest=InventoryCommandServiceTest,InventoryMovementServiceTest" test
```

Expected: PASS.

### Task 4: Atomic order confirmation command and API

**Files:**
- Create: `backend/src/main/java/com/distribuidora/order/application/OrderConfirmationService.java`
- Create: `backend/src/main/java/com/distribuidora/order/api/OrderConfirmationController.java`
- Create: `backend/src/test/java/com/distribuidora/order/OrderConfirmationServiceTest.java`

**Interfaces:**
- Consumes `PricingQueryService`, `OrderCalculationService`, `InventoryMovementService`, `JdbcTemplate`, and `AuditService`.
- Produces `ConfirmationResponse confirm(ConfirmationRequest request)` and `POST /api/orders/confirm` returning `201`.

- [ ] **Step 1: Write failing command tests**

Cover these transaction cases:

```text
successful full cash confirmation
partial cash + CUSTOMER_ACCOUNT confirmation
no payments => full customer-account debit
price override without ADMIN_ALL => 403
missing price/customer/product => 404
payment sum over total => 409
inventory failure => no order, sale, payment, ledger, balance, or stock changes
same idempotency key and same payload => original response
same idempotency key with different payload => 409
```

- [ ] **Step 2: Implement idempotency and authorization**

Look up `orders.orders` by `idempotency_key` before creating rows. Canonicalize the request payload for comparison or persist a request fingerprint alongside the key. Catch unique-key races and return the committed original result. Require `ORDER_CREATE`; reject non-null overrides unless authentication has `ADMIN_ALL`.

- [ ] **Step 3: Implement pricing and calculations**

For each line, call `PricingQueryService.resolve(customerId, productId, priceListId)`, replace with `unitPriceOverride` only for admins, and pass resolved values to `OrderCalculationService`.

- [ ] **Step 4: Implement stock, order, sale, and snapshots**

Sort distinct product IDs lexicographically before locking. Apply negative `SALE` movements, create order/sale rows, and insert order/sale items with product name, price-list ID/code, unit price, line discount, quantity, and line total snapshots.

- [ ] **Step 5: Implement payments and account ledger**

Insert `CASH`/`BANK_TRANSFER` payments and sum them into `sale.paid`. Insert a `DEBIT` ledger row and increment `customer.customers.balance` for each `CUSTOMER_ACCOUNT` amount. If payments are omitted, create one account debit for the full total. Return paid and balance in the response.

- [ ] **Step 6: Implement the controller**

Use `@Valid @NotNull @RequestBody ConfirmationRequest`, `@PreAuthorize("hasAuthority('ORDER_CREATE')")`, and return `201 Created`. Map validation, authorization, missing-resource, and business conflict errors to the existing ProblemDetail contracts.

- [ ] **Step 7: Run command tests**

Run:

```powershell
mvn -q "-Dtest=OrderConfirmationServiceTest" test
```

Expected: PASS.

### Task 5: Read APIs, documentation, and integration verification

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java`
- Modify: `backend/src/main/java/com/distribuidora/dashboard/api/ReadQueryController.java`
- Modify: `docs/development/backend-implementation-roadmap.md`
- Modify: `backend/README.md`
- Test: `backend/src/test/java/com/distribuidora/order/OrderConfirmationIntegrationTest.java`

**Interfaces:**
- Produces order detail lookup with item snapshots and sale/payment/account totals.
- Documents `POST /api/orders/confirm`, request/response schemas, idempotency, and error behavior.

- [ ] **Step 1: Add confirmation read coverage**

Add a query endpoint for a persisted order by ID or order number, exposing status, customer, totals, snapshots, paid amount, and balance. Add tests for snapshot fields and account ledger totals.

- [ ] **Step 2: Add PostgreSQL integration coverage**

Use the existing integration approach or a disposable PostgreSQL container to apply V1-V6 and confirm one real order. Verify rows in orders, sales, items, payments/ledger, customer balance, and stock movement are committed together.

- [ ] **Step 3: Update roadmap and README**

Mark confirmation, snapshots, payments, account debit, inventory sale movement, and idempotency implemented. Leave delivery, cancellation, editing, outbox, and documents explicitly pending.

- [ ] **Step 4: Run final verification**

Run:

```powershell
mvn test
mvn package -DskipTests
npm run build --prefix frontend
docker compose up -d --build
docker compose config
docker compose ps
```

Wait for PostgreSQL/backend health and execute the documented smoke test twice with the same idempotency key. Expected: the second call returns the original order without duplicate sale, payment, ledger, or stock movement.
