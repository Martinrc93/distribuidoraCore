# Single Inventory Without Depots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove depots throughout the app and use one stock balance per product for sales, returns, cancellations, edits, and adjustments.

**Architecture:** A forward Flyway migration consolidates per-depot balances into one product-keyed balance and removes depot columns/tables. Backend inventory commands and all commerce stock lifecycle paths then write directly to that balance. Frontend inventory and order pages expose no depot selection or management; docs and tests describe the single-stock model.

**Tech Stack:** Java 21, Spring Boot 3.5.16, PostgreSQL 16+, Flyway, JUnit 5, React 19, TypeScript, TanStack Query, Vitest, Testing Library.

## Global Constraints

- The business has one stock balance per product and no depot selection or depot administration.
- Order creation does not show the current explanatory subtitle or any depot field, and the confirmation API does not accept a depot identifier.
- Inventory is keyed by product only. A stock movement records the product and quantity delta, without a depot association.
- On migration, existing balances for a product are summed into its single balance. Existing movement and order/sale records remain, but their depot attribution is removed.
- Transfers between depots are no longer a supported operation.
- Migration must be safe on the existing V23 schema and run transactionally under the repository's Flyway/PostgreSQL setup. No historical migration is edited.
- Existing transfer movement rows remain historical movement records; the application no longer creates new transfers.
- Existing stock locking and transaction boundaries continue to prevent lost updates.
- No depot compatibility mode or replacement location abstraction is introduced.

---

## File Map

### Database and backend inventory

- Create `backend/src/main/resources/db/migration/V24__unify_inventory_without_depots.sql` — merge balances and remove depot schema.
- Modify `backend/src/main/java/com/distribuidora/inventory/application/InventoryMovementService.java` — lock and mutate the single product balance; append location-free movement history.
- Modify `backend/src/main/java/com/distribuidora/inventory/application/InventoryCommandService.java` and `backend/src/main/java/com/distribuidora/inventory/api/InventoryCommandController.java` — keep adjustments and remove transfers/depot request fields.
- Delete `backend/src/main/java/com/distribuidora/inventory/application/InventoryDepotService.java` and `backend/src/main/java/com/distribuidora/inventory/api/InventoryDepotController.java` — remove depot management and per-depot balances endpoints.
- Modify `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java` — query direct balances and movements without depot joins/properties.
- Modify `backend/src/main/java/com/distribuidora/demo/DemoDataSeeder.java` — seed one product balance. `ProductCommandService` already initializes balances by `product_id` and needs no change.
- Create a PostgreSQL migration test under `backend/src/test/java/com/distribuidora/inventory/` — migrate to V23, seed multiple depot balances/history, migrate to latest, and assert sum plus data retention/schema removal.
- Modify inventory unit tests, `backend/src/test/java/com/distribuidora/security/SecurityChainAuthorizationTest.java`, and integration tests to assert single-stock APIs and no depot routes/fields.
- Modify `backend/src/main/java/com/distribuidora/shared/error/ApiExceptionHandler.java` and its test so removed routes resolve to `404 NOT_FOUND`, not generic `500`.

### Backend order and sale lifecycles

- Modify `backend/src/main/java/com/distribuidora/order/api/OrderConfirmationDtos.java` and `backend/src/main/java/com/distribuidora/order/application/OrderConfirmationService.java` — remove depot from confirmation command, fingerprint, writes, and edits.
- Modify `backend/src/main/java/com/distribuidora/order/application/DeliveryLifecycleService.java` and `backend/src/main/java/com/distribuidora/sale/application/SaleReturnService.java` — restore stock by product only.
- Modify order, delivery lifecycle, and sale-return unit tests plus `backend/src/test/java/com/distribuidora/integration/PostgresBackendFixesIntegrationTest.java` — cover confirmation, edit, return, and cancellation against one balance.

### Frontend

- Modify `frontend/src/features/orders/OrderCreatePage.tsx` and its test — remove subtitle, depot query/state/validation/selector/payload behavior.
- Delete `frontend/src/features/inventory/DepotInventorySection.tsx` and its test — depot administration and transfer UI no longer has a component boundary to maintain.
- Modify `frontend/src/features/inventory/InventoryPage.tsx` and its test — make it the sole searchable/paginated stock list and adjustment/movement owner; remove the duplicate all-products table plus depot state, depot column, depot copy, and depot-specific invalidation.

### Documentation and verification

- Update `docs/api/inventory.md`, `backend/README.md`, `docs/domain/functionalities.md`, `docs/architecture/overview.md`, `docs/architecture/module-boundaries.md`, `docs/diagrams/inventory/`, and development checklists/roadmaps that describe multi-depot behavior.
- Update `docs/development/testing-strategy.md`; verify `scripts/order-confirmation-smoke.ps1` already uses product-only stock and does not need to touch the persistent Compose volume.

## Task 1: Migrate the schema and simplify inventory writes

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__unify_inventory_without_depots.sql`
- Modify: `backend/src/main/java/com/distribuidora/inventory/application/InventoryMovementService.java`
- Modify: `backend/src/main/java/com/distribuidora/inventory/application/InventoryCommandService.java`
- Modify: `backend/src/main/java/com/distribuidora/inventory/api/InventoryCommandController.java`
- Delete: `backend/src/main/java/com/distribuidora/inventory/application/InventoryDepotService.java`
- Delete: `backend/src/main/java/com/distribuidora/inventory/api/InventoryDepotController.java`
- Modify: `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java`
- Modify: `backend/src/main/java/com/distribuidora/demo/DemoDataSeeder.java`
- Modify: `backend/src/main/java/com/distribuidora/shared/error/ApiExceptionHandler.java`
- Modify: `backend/src/test/java/com/distribuidora/inventory/InventoryMovementServiceTest.java`
- Modify: `backend/src/test/java/com/distribuidora/inventory/InventoryCommandServiceTest.java`
- Modify: `backend/src/test/java/com/distribuidora/security/SecurityChainAuthorizationTest.java`
- Modify: `backend/src/test/java/com/distribuidora/shared/error/ApiExceptionHandlerTest.java`
- Create: `backend/src/test/java/com/distribuidora/inventory/SingleInventoryMigrationIntegrationTest.java`

**Interfaces:**
- Adjustment endpoint remains `POST /api/inventory/{productId}/adjustments` with body `{ "quantity": number, "reason": string }` and requires `STOCK_ADJUST`.
- Inventory movement operation becomes `apply(UUID productId, BigDecimal delta, String movementType, UUID referenceId, String reason)`.
- `InventoryCommandService.adjust(UUID productId, BigDecimal quantity, String reason)` delegates to the location-free movement operation.
- No `/api/inventory/depots` or `/api/inventory/transfers` endpoint remains.

- [x] **Step 1: Add failing unit tests for the single-balance write contract**

In `InventoryMovementServiceTest`, verify the SQL locks/updates `inventory.inventory_balances` by `product_id` only, and inserts into `inventory.stock_movements` without `depot_id`. In `InventoryCommandServiceTest`, assert adjustment audit details contain quantity/reason only. Remove transfer-specific test cases and replace them with a controller contract test that rejects the removed `/api/inventory/transfers` route as not found.

Expected movement persistence statements:

```sql
select quantity from inventory.inventory_balances where product_id = ? for update
update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?
insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)
```

- [x] **Step 2: Run focused inventory tests and confirm they fail against depot-based code**

Run from `backend/`:

```powershell
mvn -Dtest=InventoryMovementServiceTest,InventoryCommandServiceTest test
```

Expected: compilation or assertions fail because current services and persistence statements still require depot IDs.

- [x] **Step 3: Write the migration integration test for consolidated quantities**

Add `SingleInventoryMigrationIntegrationTest` using PostgreSQL Testcontainers. Start with Flyway targeted at V23, create a second depot and two balances for one product (`3.5` and `2.0`), add movement/order/sale rows with depot attribution, run Flyway to latest, and assert one balance of `5.5`, preserved movement/order/sale records, removed depot attribution columns, and no `inventory.depots` relation. Include SQL metadata assertions for `product_id` as the sole inventory balance primary key.

Test sequence:

```java
flyway.configure().dataSource(url, username, password)
    .target(MigrationVersion.fromVersion("23")).load().migrate();
// Seed V23 depot rows and records, then run Flyway without target and assert V24 results.
```

- [x] **Step 4: Run the migration integration test and confirm it fails before the migration is added**

Run from `backend/`:

```powershell
mvn -Dtest=SingleInventoryMigrationIntegrationTest test
```

Expected: test fails against the existing schema because V24 and single-balance structure do not exist.

- [x] **Step 5: Implement V24 and verify the migration test passes**

Implement V24 as a transaction-safe PostgreSQL migration. Aggregate balances using `sum(quantity)` grouped by `product_id` and `max(updated_at)`, replace the composite balance key with a product-only primary key, remove depot columns/foreign keys/indexes from balances, movements, orders, and sales, and drop `inventory.depots`. Preserve all movement/order/sale rows and all business fields except depot attribution. Keep historical `TRANSFER_IN`/`TRANSFER_OUT` rows and allowed historical type values; application code will no longer create transfer rows.

Run:

```powershell
mvn -Dtest=SingleInventoryMigrationIntegrationTest test
```

Expected: migration test passes and the sum/retention/schema assertions succeed.

- [x] **Step 6: Implement single-stock movement and adjustment services**

Remove `DEFAULT_DEPOT_ID`, depot lookup, depot parameters, transfer operations, `TransferRequest`, and `TransferResponse`. Keep product-active checks, supported movement validation, `SELECT ... FOR UPDATE`, timestamp updates, movement reason/reference, transaction annotations, and audit event. Adjustment requests contain only `quantity` and `reason`. Remove both depot application/controller classes and remove their Spring wiring/imports. Map Spring's missing-route exception to `404 NOT_FOUND` so removed APIs do not fall through the generic `500` handler.

- [x] **Step 7: Update inventory reads, verify product initialization, and update demo seed**

In `ReadQueryService`, return `inventory_balances.quantity` directly, and omit depot IDs/codes from movement DTO maps. Verify `ProductCommandService` already initializes balances using only `product_id`; keep that correct insert unchanged. Update `DemoDataSeeder` stock inserts and decrements to use only `product_id`. Remove depot controller/service from security test setup and assert old depot routes return `404 NOT_FOUND`.

- [x] **Step 8: Run backend inventory and migration checks**

Run from `backend/`:

```powershell
mvn -Dtest=InventoryMovementServiceTest,InventoryCommandServiceTest,SingleInventoryMigrationIntegrationTest,SecurityChainAuthorizationTest test
```

Expected: all focused tests pass; migration test proves aggregation and record retention.

## Task 2: Remove depot associations from orders, returns, and cancellations

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/order/api/OrderConfirmationDtos.java`
- Modify: `backend/src/main/java/com/distribuidora/order/application/OrderConfirmationService.java`
- Modify: `backend/src/main/java/com/distribuidora/order/application/DeliveryLifecycleService.java`
- Modify: `backend/src/main/java/com/distribuidora/sale/application/SaleReturnService.java`
- Modify: `backend/src/test/java/com/distribuidora/order/OrderConfirmationServiceTest.java`
- Modify: `backend/src/test/java/com/distribuidora/order/DeliveryLifecycleServiceTest.java`
- Modify: `backend/src/test/java/com/distribuidora/sale/SaleReturnServiceTest.java`
- Modify: `backend/src/test/java/com/distribuidora/integration/PostgresBackendFixesIntegrationTest.java`

**Interfaces:**
- Confirmation JSON contains no `depotId`; `ConfirmationCommand` and its implementations expose no depot accessor.
- Order and sale writes omit depot columns. Idempotency fingerprints include only confirmation business inputs, not location.
- All sale/edit/return/cancellation stock deltas call `apply(productId, delta, movementType, referenceId, reason)`.

- [x] **Step 1: Write failing unit tests for depot-free confirmation payload and single-stock lifecycle**

Update confirmation tests to construct commands without depot arguments and verify SQL writes omit depot columns. Update cancellation tests to group original `SALE` movement deltas by `product_id`, not `(depot_id, product_id)`, and verify positive reversal movements use the same product-only balance. Update return/edit tests to expect the location-free `InventoryMovementService.apply` signature.

- [x] **Step 2: Run focused commerce tests and confirm they fail**

Run from `backend/`:

```powershell
mvn -Dtest=OrderConfirmationServiceTest,DeliveryLifecycleServiceTest,SaleReturnServiceTest test
```

Expected: compile/assertion failures identify remaining depot command accessors, SQL columns, or movement calls.

- [x] **Step 3: Remove depot from confirmation API, fingerprint, and persistence**

Remove `depotId` from `ConfirmationRequest`, `ConfirmationCommand`, and `ConfirmationData`. Remove depot contribution from `fingerprint`. Confirmation and confirmed-order edit stock changes use the single-balance movement method. Insert `orders.orders` and `sale.sales` rows without `depot_id` columns.

- [x] **Step 4: Make reversals and returns product-only**

Change cancellation to select and group movement quantities by `product_id` only. Change `SaleReturnService` to restore each returned product directly to the single balance, without loading the sale depot. Keep sale/order state validation, paid-sale restrictions, audit behavior, transaction boundaries, and movement references.

- [x] **Step 5: Update PostgreSQL end-to-end regression coverage**

Replace `stockTransfersAndOrderLifecyclePreserveSelectedDepot` with single-inventory lifecycle coverage using separate orders: one order verifies confirmation, confirmed-order edit, and a partial return against a single product balance; a separate unpaid order verifies cancellation restores its sale quantity exactly once. Assert order/sale rows and movement JSON have no depot property/column; remove depot fixtures/cleanup/imports from the integration class.

- [x] **Step 6: Run backend commerce and integration verification**

Run from `backend/`:

```powershell
mvn -Dtest=OrderConfirmationServiceTest,DeliveryLifecycleServiceTest,SaleReturnServiceTest,OrderConfirmationControllerTest,PostgresBackendFixesIntegrationTest test
```

The PostgreSQL integration class runs only when `POSTGRES_TEST_URL` is configured; run the regular suite regardless, then rerun against a disposable PostgreSQL database with that environment variable set. Do not point it at or delete the persistent Compose volume.

## Task 3: Replace depot-oriented frontend inventory and order flows

**Files:**
- Modify: `frontend/src/features/orders/OrderCreatePage.tsx`
- Modify: `frontend/src/features/orders/OrderCreatePage.test.tsx`
- Modify: `frontend/src/shared/components/PageHeader.tsx` — make description optional and omit its paragraph when absent.
- Modify: `frontend/src/features/inventory/InventoryPage.tsx`
- Modify: `frontend/src/features/inventory/InventoryPage.test.tsx`
- Delete: `frontend/src/features/inventory/DepotInventorySection.tsx`
- Delete: `frontend/src/features/inventory/DepotInventorySection.test.tsx`

**Interfaces:**
- `POST /api/orders/confirm` body excludes `depotId` for every authority.
- Adjustment body is `{ quantity, reason }`.
- `GET /api/inventory` returns one row per product with direct balance; movement rows omit `depotId` and `depotCode`.

- [x] **Step 1: Add failing order-page assertions**

Update `OrderCreatePage.test.tsx` to assert the explanatory subtitle and depot label are absent, an admin confirmation does not request `/api/inventory/depots`, and posted confirmation JSON has no `depotId`. Remove tests that change a selected depot between idempotent retries; keep the exact same retry payload/key test for other order data.

- [x] **Step 2: Run the focused frontend order test and confirm it fails**

Run from `frontend/`:

```powershell
npm run test -- src/features/orders/OrderCreatePage.test.tsx
```

Expected: current component still renders the subtitle and may issue depot queries.

- [x] **Step 3: Remove order subtitle and depot behavior**

Remove the `PageHeader` description from the new-order header, depot type/query/key/state/effect, depot loading/error handling, depot validation, selector, and `depotId` from `ConfirmationRequest` and payload construction. Keep customer, product, price-list, discount, payment, confirmation, and idempotent retry behavior unchanged.

- [x] **Step 4: Add failing tests for the unified stock list and adjustments**

In `InventoryPage.test.tsx`, test that each product and total stock appears once in the searchable/paginated list; adjustment sends `POST /api/inventory/{productId}/adjustments` with only quantity and reason; movement history shows type, quantity, reason, reference, and date without a depot column; no depot-management or transfer controls are rendered. Replace `DepotInventorySection.test.tsx` with these assertions before deleting that test file.

- [x] **Step 5: Implement the unified single-stock inventory UI**

Move the searchable and paginated balance list into `InventoryPage` and remove its current duplicate non-searchable balance table. Remove `DepotInventorySection` and its mount. Keep one list, adjustment dialog, movement history, existing quantity/reason validation, success/error feedback, and query invalidation for `/api/inventory` and selected product movements. Remove depot-specific copy, types, payload fragments, and cache predicates.

- [x] **Step 6: Run focused frontend tests and build**

Run from `frontend/`:

```powershell
npm run test -- src/features/orders/OrderCreatePage.test.tsx src/features/inventory/InventoryPage.test.tsx
npm run build
```

Expected: focused tests pass and TypeScript/Vite build succeeds. If the inventory component is renamed, use its replacement test path in this command.

## Task 4: Align docs and operational smoke verification

**Files:**
- Modify: `docs/api/inventory.md`
- Modify: `docs/api/order-edits.md`, `docs/api/sale-returns.md`, and `docs/adr/ADR-006-inventory-and-stock-consistency.md`
- Modify: `docs/architecture/data-architecture.md`
- Modify: `docs/diagrams/inventory/README.md`, `docs/diagrams/inventory/flow-return.md`, `docs/diagrams/inventory/flow-manual-adjustment.md`, and `docs/diagrams/inventory/flow-confirmed-order-delta.md`
- Delete: `docs/diagrams/inventory/flow-depot-transfer.md` and `docs/diagrams/inventory/er-inventory-depots.md`
- Modify: `backend/README.md`
- Modify: `docs/domain/functionalities.md`
- Modify: `docs/architecture/overview.md`
- Modify: `docs/architecture/module-boundaries.md`
- Modify: `docs/development/backend-implementation-roadmap.md`
- Modify: `docs/development/backend-functionality-status.md`
- Modify: `docs/development/backend-checklist.md`
- Modify: `docs/development/frontend-checklist.md`
- Modify: `docs/development/implementation-checklist.md`
- Modify: `docs/development/frontend-backend-gaps.md`
- Modify: `docs/development/testing-strategy.md`
- Verify: `scripts/order-confirmation-smoke.ps1` already uses product-only stock and no depot field.
- Modify: `docs/superpowers/specs/2026-09-18-inventory-design.md` to mark its multi-depot scope as historical.

- [x] **Step 1: Update API and domain documentation**

Document product-keyed inventory balances, location-free movement response fields, adjustment request `{ "quantity": ..., "reason": ... }`, and removal of depot/transfer endpoints. Update order confirmation examples to state that no depot field is accepted. Describe delivery cancellation and returns as product-only stock reversals. Remove diagrams or references that assert per-depot behavior; update affected architecture docs and development checklists/statuses.

- [x] **Step 2: Review smoke assertions already using product-only stock**

`order-confirmation-smoke.ps1` already snapshots `inventory_balances.quantity` by `product_id`, verifies confirmation/delivery/cancellation changes, and sends no depot field. No script change was needed. Its Compose runner targets the persistent local database; leave that volume untouched and verify the lifecycle against the disposable PostgreSQL integration database instead.

- [x] **Step 3: Review remaining product documentation for stale depot contracts**

Search `docs/`, `scripts/`, `backend/`, and `frontend/` for `depot`, `depots`, `depot_id`, `Depósito`, `transferencias` and inspect each remaining match. Retain only historical implementation-plan/spec references where editing would falsify what was implemented at that time; update current-state docs and remove obsolete operational/API contracts.

- [x] **Step 4: Run final verification**

Run from `backend/`:

```powershell
mvn test
```

Run from `frontend/`:

```powershell
npm run test
npm run build
```

Then run the V24 migration integration test and `PostgresBackendFixesIntegrationTest` against a disposable PostgreSQL database. The existing Compose smoke script already uses product-only balance queries; do not run it against or alter the persistent Compose volume. Review `git diff --check`, inspect the full diff, and verify current runtime/API/docs contain no depot concepts, excluding explicitly historical implementation records.

## Self-review against the approved design

- Single product-keyed balance and summing migration: Task 1 and the PostgreSQL migration test.
- Depot-free movements, adjustments, inventory read APIs, and removal of management/transfers: Task 1.
- Order API/fingerprint/persistence and order edits: Task 2.
- Returns and cancellation stock restore: Task 2.
- Removed order subtitle, field, and frontend inventory depot UI: Task 3.
- Migration/data retention, authorization, transaction/locking, smoke checks, and current docs: Tasks 1, 2, and 4.
- Historical movement/order/sale rows remain while depot attribution is dropped; historical transfer movement types remain only as stored movement types: Tasks 1, 2, and database migration.

No spec requirement is left without an implementation and verification task.
