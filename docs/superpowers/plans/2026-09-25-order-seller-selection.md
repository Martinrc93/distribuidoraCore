# Order Seller Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show Client, Seller and Price List in one row and let administrators choose which existing seller owns a newly confirmed order.

**Architecture:** The order form reads the customer's assigned seller and, for administrators only, the existing seller list. Confirmation gains an optional `sellerId`; the backend uses it only for administrators, validates it before stock writes, persists it on the order and includes it in the idempotency fingerprint. Non-admin seller users continue to be attributed to their authenticated seller profile.

**Tech Stack:** Java 21, Spring Boot 3.5.16, PostgreSQL 16+, JUnit 5, React 19, TypeScript, TanStack Query, Vitest, Testing Library, Vite.

## Global Constraints

- In desktop order creation, Client occupies the current left half; Seller and Price List split the right half equally.
- The selected customer's assigned seller is the initial seller selection.
- Only users with `ADMIN_ALL` can change the order seller; other users see the customer's seller read-only, while backend continues using the authenticated seller profile.
- A chosen seller is stored in `orders.orders.seller_id`; changing it does not update `customer.customers.seller_id`.
- Changing customer resets seller to that customer's assigned seller.
- The seller field stacks with the other fields at the existing mobile breakpoint.
- No database migration or new seller-list endpoint is introduced.

---

## File Map

- Modify `frontend/src/features/orders/OrderCreatePage.tsx` — seller list query, selected/default state, one-row three-field layout and confirmation payload.
- Modify `frontend/src/features/orders/OrderCreatePage.test.tsx` — layout, default assignment, admin override, non-admin read-only and retry behavior.
- Modify `frontend/src/styles.css` — local responsive grid proportions for the three initial order fields.
- Modify `backend/src/main/java/com/distribuidora/order/api/OrderConfirmationDtos.java` — optional `sellerId` in the confirmation JSON and six-argument Java convenience constructor.
- Modify `backend/src/main/java/com/distribuidora/order/application/OrderConfirmationService.java` — role-aware seller resolution, existence validation, order persistence and fingerprint.
- Modify `backend/src/test/java/com/distribuidora/order/OrderConfirmationServiceTest.java` — administrator override, customer default, invalid seller and non-admin protection.
- Modify `backend/src/test/java/com/distribuidora/integration/PostgresBackendFixesIntegrationTest.java` — PostgreSQL assertion that the chosen seller is persisted without changing customer assignment.
- Modify `backend/README.md` — confirmation request contract and seller-assignment rules.
- Modify `docs/development/backend-functionality-status.md`, `docs/development/backend-checklist.md`, and `docs/adr/ADR-007-order-sale-and-payment-domain.md` — current order seller attribution.

## Task 1: Extend the order confirmation contract and seller resolution

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/order/api/OrderConfirmationDtos.java`
- Modify: `backend/src/main/java/com/distribuidora/order/application/OrderConfirmationService.java`
- Modify: `backend/src/test/java/com/distribuidora/order/OrderConfirmationServiceTest.java`
- Modify: `backend/src/test/java/com/distribuidora/integration/PostgresBackendFixesIntegrationTest.java`

**Interfaces:**
- `ConfirmationRequest` adds the optional component `UUID sellerId` after `payments` and retains the existing six-argument constructor delegating with `sellerId = null`.
- `ConfirmationCommand` provides `default UUID sellerId() { return null; }`, so `ConfirmationData` used by confirmed-order edits does not need a seller override.
- Seller resolution at confirmation follows: non-admin authenticated user → `currentUser.requireSellerProfile()`; admin with non-null `sellerId` → validated requested seller; otherwise → `customer.seller_id`.
- The selected seller is written to `orders.orders.seller_id`; no seller field is added to sale tables.

- [ ] **Step 1: Add failing unit tests for administrator seller choice and defaulting**

In `OrderConfirmationServiceTest`, add a test with `ADMIN_ALL`, customer `seller_id = customerSellerId`, and request `sellerId = selectedSellerId`. Stub the seller existence query and assert the `orders.orders` insert bind values contain `selectedSellerId`. Add a second case with `sellerId = null` and assert it uses `customerSellerId`.

Core request construction:

```java
new OrderConfirmationDtos.ConfirmationRequest(
    key, customerId, null, lines, BigDecimal.ZERO, List.of(), selectedSellerId);
```

- [ ] **Step 2: Add failing tests for invalid and unauthorized seller IDs**

With `ADMIN_ALL`, make the seller existence query return false; expect a not-found/data-access exception before inventory or order writes. With a non-admin `CurrentUserAccess`, pass a different seller ID; expect `AccessDeniedException`. A no-override non-admin request must resolve to `currentUser.requireSellerProfile()` even if the customer's assigned seller ID differs.

- [ ] **Step 3: Run focused backend tests and confirm the new cases fail**

Run from `backend/`:

```powershell
mvn "-Dtest=OrderConfirmationServiceTest,OrderConfirmationControllerTest" test
```

Expected: compilation or assertions fail because the confirmation command currently has no seller selection contract.

- [ ] **Step 4: Add sellerId to the DTO and command contract**

Add `UUID sellerId` after the payments component of `ConfirmationRequest`. Preserve the existing Java call sites with this overload:

```java
public ConfirmationRequest(String idempotencyKey, UUID customerId, UUID priceListId,
                           List<LineRequest> lines, BigDecimal orderDiscountPercent,
                           List<PaymentRequest> payments) {
    this(idempotencyKey, customerId, priceListId, lines, orderDiscountPercent, payments, null);
}
```

Add `default UUID sellerId() { return null; }` to `ConfirmationCommand`.

- [ ] **Step 5: Resolve and validate seller before inventory mutation**

Change confirmation to resolve seller immediately after validating the active customer and before `resolveLines` or inventory updates. For an authenticated non-admin, use the authenticated seller profile and reject a non-null request ID that differs. For an administrator (or the existing service-test context without a user object), use the request ID when non-null; otherwise use the customer's assigned `seller_id`. If the result is non-null, verify a row exists in `seller.seller_profiles`; let a missing seller map to `404`.

- [ ] **Step 6: Persist seller selection and include it in idempotency**

Pass the resolved seller ID into both branches of the `orders.orders` insert, including the service context without `CurrentUserAccess`. Append `request.sellerId()` to the canonical confirmation fingerprint so a seller change after a failed attempt creates a new idempotency fingerprint; an unchanged seller preserves exact retry behavior.

- [ ] **Step 7: Add PostgreSQL seller-persistence coverage**

In `PostgresBackendFixesIntegrationTest`, create two seller profiles and a customer assigned to the first. Confirm as `ADMIN_ALL` with the second seller selected. Assert `orders.orders.seller_id` equals the selected seller while `customer.customers.seller_id` remains the original assignment.

- [ ] **Step 8: Run focused backend tests**

Run from `backend/`:

```powershell
mvn "-Dtest=OrderConfirmationServiceTest,OrderConfirmationControllerTest" test
```

Expected: all focused tests pass, including seller override/fallback, permission enforcement, and validation-before-side-effects.

## Task 2: Add seller selection to the order form and match the approved layout

**Files:**
- Modify: `frontend/src/features/orders/OrderCreatePage.tsx`
- Modify: `frontend/src/features/orders/OrderCreatePage.test.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Customer response fields used: `{ id, name, sellerId?, seller?, priceListId?, balance? }`.
- Admin seller list query: `GET /api/sellers?page=0&size=100`, response items `{ id, displayName, email }[]`; the existing endpoint requires `ADMIN_ALL` and returns all seller profiles.
- Admin order confirmation payload includes `sellerId: UUID | null`; non-admin payload omits `sellerId`.

- [ ] **Step 1: Add failing frontend tests for field order, widths and seller query**

Extend the customer fixture with `sellerId: 'seller-1'` and `seller: 'Lucía'`. Mock `/api/sellers` with two seller profiles. For an administrator, select a customer and assert the seller selector defaults to `seller-1`, seller and price-list controls follow the customer field in DOM order, and the seller query is called. For a non-admin, assert seller displays as read-only text and `/api/sellers` is not requested.

- [ ] **Step 2: Add failing frontend tests for seller override, customer switch and retry**

For an admin, select `seller-2`, confirm and assert request JSON includes `sellerId: 'seller-2'`. Switch to a different customer and assert the seller selection resets to that customer's `sellerId`. Simulate a failed confirmation followed by retry and assert both payloads (including `sellerId`) are byte-identical.

- [ ] **Step 3: Run the focused frontend test and confirm it fails**

Run from `frontend/`:

```powershell
npm run test -- src/features/orders/OrderCreatePage.test.tsx
```

Expected: the current form has no seller field/query and retains a two-column Client/Price List row.

- [ ] **Step 4: Load seller/customer defaults with existing permission boundaries**

Extend `Customer` with optional `sellerId` and `seller`; add `Seller` `{ id, displayName, email }`; query `/api/sellers?page=0&size=100` only when `isAdmin`. Include seller query loading/error in the existing order preparation state only for administrators. When Customer changes, set `sellerId` to the chosen customer's `sellerId ?? ''` and clear explicit price list as before.

- [ ] **Step 5: Render the three fields in the approved desktop layout**

Replace the initial `.form-grid` with `.order-customer-grid`. Render Cliente, Vendedor and Lista de precios in that DOM order. For admins, Vendedor is a selector whose options come from the sellers query; for other users it is read-only text from `customer.seller`, or “Seleccioná un cliente” before selection. Keep Price List resolution and selection behavior unchanged.

Set the grid in `frontend/src/styles.css`:

```css
.order-customer-grid { display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 14px; margin-bottom: 25px; }
```

At `@media (max-width: 760px)`, collapse `.order-customer-grid` to `grid-template-columns: 1fr` alongside the existing form-grid collapse.

- [ ] **Step 6: Include sellerId in admin payload and invalidate draft attempts on changes**

Extend `ConfirmationRequest` with optional nullable `sellerId`. Add `sellerId` to `buildPayload()` only for administrators. Call `draftChanged()` when admin changes the seller. Keep the exact `attempt` object on retries so the selected seller remains unchanged.

- [ ] **Step 7: Run focused frontend tests and build**

Run from `frontend/`:

```powershell
npm run test -- src/features/orders/OrderCreatePage.test.tsx
npm run build
```

Expected: seller layout/selection/read-only/default/retry tests pass and the production TypeScript/Vite build succeeds.

## Task 3: Document the current seller-attribution rule and verify end to end

**Files:**
- Modify: `backend/README.md`
- Modify: `docs/development/backend-functionality-status.md`
- Modify: `docs/development/backend-checklist.md`
- Modify: `docs/adr/ADR-007-order-sale-and-payment-domain.md`
- Modify: `docs/development/testing-strategy.md`

- [ ] **Step 1: Update the API and architecture documentation**

Document optional `sellerId` on admin order confirmation, admin defaulting from customer assignment, seller persistence on `orders.orders.seller_id`, and server enforcement that a non-admin order remains assigned to the authenticated seller profile. State that changing an order seller does not reassign the customer.

- [ ] **Step 2: Run complete backend and frontend suites**

Run from `backend/`:

```powershell
mvn test
```

Run from `frontend/`:

```powershell
npm run test
npm run build
```

- [ ] **Step 3: Run PostgreSQL confirmation integration tests on a disposable database**

Set `POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME`, and `POSTGRES_TEST_PASSWORD` to a disposable PostgreSQL 16 database, then run from `backend/`:

```powershell
mvn -Dtest=PostgresBackendFixesIntegrationTest test
```

Expected: Flyway V1–V24 and JPA validation pass; seller override is persisted to the order without changing the customer's seller assignment. Do not use or remove the persistent Compose volume.

## Self-review against the approved design

- Client field retains the existing left half; Seller and Price List divide the right half, with mobile stacking: Task 2.
- Seller defaults from selected customer and resets on customer change: Task 2.
- Admin can choose from `/api/sellers`; non-admin cannot override authenticated profile: Tasks 1 and 2.
- Seller ID is persisted to the order, included in idempotency fingerprint, and does not update the customer: Tasks 1 and 3.
- Loading/errors, invalid seller validation, retry payload consistency, and PostgreSQL integration are covered: Tasks 1–3.

No approved requirement is left without an implementation and verification task.
