# Backend-Driven Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the implemented backend contracts into responsive, permission-aware frontend workflows and document capabilities that cannot be exposed because no user-facing API exists.

**Architecture:** Preserve the React Router/TanStack Query application shell and extract pages from `App.tsx` into domain modules under `frontend/src/features`. Keep a shared authenticated API client and use backend DTOs as the source of truth for request/response types; each domain page owns its forms, query keys, mutation feedback and focused tests.

**Tech Stack:** React 19, TypeScript, React Router 7, TanStack Query 5, Vitest, React Testing Library, Vite.

## Global Constraints

- Do not calculate authoritative prices, stock, discounts, payment allocations, credit decisions, or delivery state in the frontend.
- Use JWT authorities only to show or hide actions; the backend remains the authorization authority.
- Preserve idempotency keys and request payloads when retrying the same order confirmation.
- Keep controlled forms and current dependencies; do not add a form library.
- Every module provides loading, specific-empty, read-error, mutation-success, and actionable mutation-error states.
- Require confirmation for deactivation, cancellation, returns, and other irreversible actions.
- Responsive pages must remain keyboard accessible and use the installed `frontend-design` visual direction.
- Update `docs/development/frontend-checklist.md` only after each listed acceptance criterion is implemented and tested.

---

### Task 1: Shared API contracts, session renewal, and visual shell

**Files:**
- Modify: `frontend/src/shared/api/client.ts`
- Modify: `frontend/src/shared/api/client.test.ts`
- Modify: `frontend/src/shared/auth/permissions.ts`
- Modify: `frontend/src/shared/auth/permissions.test.ts`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/app/App.test.tsx`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/src/shared/components/DataTable.tsx`

**Interfaces:**
- `ApiError` exposes `status`, `code`, `detail`, and optional `affectedPriceLists` parsed from the backend ProblemDetail envelope.
- `login()` stores `accessToken` and nullable `refreshToken`; concurrent 401 responses share one `/api/auth/refresh` rotation and each original request retries only once.
- `logout()` posts `{ refreshToken }` to `/api/auth/logout` and clears both tokens even if the server rejects revocation.
- `apiGet`, `apiPost`, `apiPut`, and `apiPatch` support JSON and 204 responses consistently; `apiGetBlob(path, filename)` downloads authenticated PDF responses.

- [x] **Step 1: Add failing API-client tests** for login token storage, refresh request/rotation after 401, one retry only, logout cleanup, ProblemDetail metadata, and 204 responses.
- [x] **Step 2: Run the focused tests** with `npm test -- --run src/shared/api/client.test.ts`; confirm failures describe the missing behaviors.
- [x] **Step 3: Implement typed token storage, single-flight refresh, and request retry** in `client.ts`; keep a failed refresh from recursively refreshing and clear credentials on refresh 401/403.
- [x] **Step 4: Run the API-client tests** and confirm all new cases pass.
- [x] **Step 5: Add failing permission/shell tests** for permission-aware navigation and absent authorities, plus logout routing.
- [x] **Step 6: Add failing shell tests** proving unknown roles see no admin-only navigation and invalid/missing session data cannot display a fabricated user identity.
- [x] **Step 7: Update the shell and styles** with the approved mineral/petrol/green/copper palette, Aptos/Segoe UI stack, clear focus states, reduced-motion support, mobile navigation, actual session logout, and no fabricated current-user identity.
- [x] **Step 8: Run `npm test -- --run src/shared/api/client.test.ts src/shared/auth/permissions.test.ts src/app/App.test.tsx` and `npm run build` from `frontend`; fix regressions before continuing.

### Task 2: Product form, price lists, brands, and categories

**Files:**
- Modify: `frontend/src/features/products/ProductsPage.tsx`
- Modify: `frontend/src/features/products/ProductsPage.test.tsx`
- Create: `frontend/src/features/pricing/PriceListsPage.tsx`
- Create: `frontend/src/features/pricing/PriceListsPage.test.tsx`
- Create: `frontend/src/features/catalog/CatalogAdminPage.tsx`
- Create: `frontend/src/features/catalog/CatalogAdminPage.test.tsx`
- Modify: `frontend/src/app/App.tsx`

**Interfaces:**
- `Product` has no general `price`; product writes use `{ sku, name, category, presentation, cost, prices?, categoryId?, brandId? }`.
- Price reads use `GET /api/pricing/lists?page=0&size=20` and `/api/pricing/lists/{listId}/prices?page=0&size=20`; mutations use the `/api/pricing/lists` POST/PUT/PATCH and `/products/{productId}` PUT endpoints.
- Product update errors may contain `affectedPriceLists: [{ priceListId, code, currentPrice }]`; the retry sends the same product fields and a `prices` array with a replacement for every affected list.
- Brands and categories use their CRUD endpoints and direct list responses; product form choices include only `ACTIVE` options.

- [x] **Step 1: Replace product fixtures and add failing tests** for no general price input/column, list-price display, active brand/category choices, valid create payload, cost-only update, affected-list validation render/retry, and no retry while an affected price is missing or below cost.
- [x] **Step 2: Run the focused product tests** with `npm test -- --run src/features/products/ProductsPage.test.tsx`; confirm the stale product-level price contract fails.
- [x] **Step 3: Update `ProductsPage` types/form/table** to remove `price`, load active brands/categories and prices, send `categoryId`/`brandId`, and preserve existing rows when product status or category changes.
- [x] **Step 4: Implement affected-list correction** using `ApiError.affectedPriceLists`; present replacement inputs only for returned lists and retry with the matching `{ priceListId, price }` values.
- [x] **Step 5: Add tests and implement `PriceListsPage`** for list selection, create/name/status mutations, per-product price editing, loading/empty/error/success, and `ADMIN_ALL` action visibility. List mutations send `{ code, name }`, `{ name }`, or `{ status }`; price mutation sends `{ price }`.
- [x] **Step 6: Add tests and implement `CatalogAdminPage`** for brand/category list, create, rename, status/deactivation confirmation, duplicate/conflict errors, and product counts.
- [x] **Step 7: Add routes and run** all three focused test files, the complete `npm test -- --run`, and `npm run build` from `frontend`.

### Task 3: Order creation, detail, confirmation, and administrative edit

**Files:**
- Modify: `frontend/src/app/App.tsx`
- Create: `frontend/src/features/orders/OrdersPage.tsx`
- Create: `frontend/src/features/orders/OrdersPage.test.tsx`
- Create: `frontend/src/features/orders/OrderCreatePage.tsx`
- Create: `frontend/src/features/orders/OrderCreatePage.test.tsx`
- Create: `frontend/src/features/orders/OrderDetailPage.tsx`
- Create: `frontend/src/features/orders/OrderDetailPage.test.tsx`

**Interfaces:**
- Read: `GET /api/orders?page=0&size=20`, `/api/orders/{orderId}`, `/api/orders/by-number/{orderNumber}`.
- Confirm: `POST /api/orders/confirm` with `{ idempotencyKey, customerId, priceListId?, lines: [{ productId, quantity, lineDiscountPercent, unitPriceOverride? }], orderDiscountPercent, payments: [{ method, amount }] }`.
- Edit: `PUT /api/orders/{orderId}` with `{ priceListId?, lines, orderDiscountPercent }`; only `ADMIN_ALL` can edit.
- Confirmation response renders order number, sale number, total, paid, balance, and optional `creditLimitWarning`.

- [x] **Step 1: Add failing order tests** for selecting a customer/list/products, resolving list prices, payments, stable retry key, double-submit prevention and non-admin discount hiding.
- [x] **Step 2: Run `npm test -- --run src/features/orders/OrderCreatePage.test.tsx`** and confirm the current static mock order does not satisfy the tests.
- [x] **Step 3: Implement the controlled order draft** with the first API page of customers/products, explicit list selection, line add/remove/quantity/discount, and resolved read-only list prices; use client-calculated line preview only as a preview.
- [x] **Step 4: Implement confirmation and retry behavior** with one idempotency key per immutable attempt; retain the identical payload/key on retry, disable submit while pending, and show the returned order/sale/payment/credit-warning values.
- [x] **Step 5: Add orders list/detail tests** for search/status, snapshots, linked sale/payment state, permissions and missing-order handling; implement the pages using the query endpoints.
- [x] **Step 6: Add admin edit tests and form** for `PUT /api/orders/{id}`, preserving paid amount display and showing validation/stock conflicts without silently changing saved snapshots.
- [x] **Step 7: Run focused order tests, full frontend tests, and build.**

### Task 4: Inventory movements and manual adjustments

**Files:**
- Modify: `frontend/src/app/App.tsx`
- Create: `frontend/src/features/inventory/InventoryPage.tsx`
- Create: `frontend/src/features/inventory/InventoryPage.test.tsx`

**Interfaces:**
- Read balances: `GET /api/inventory?page=0&size=20`.
- Read movements: `GET /api/inventory/{productId}/movements?page=0&size=20`.
- Adjust: `POST /api/inventory/{productId}/adjustments` with `{ quantity, reason }`; requires `STOCK_ADJUST`.

- [x] **Step 1: Add failing tests** for balances, per-product movement history, signed quantity multiples of `0.5`, required reason, negative-stock warning, unauthorized actions hidden, confirmation, success feedback, and invalidated balance/movement queries.
- [x] **Step 2: Run the inventory tests** and confirm the current static adjustment button lacks an API-backed flow.
- [x] **Step 3: Implement balance and first-page movement views** with product selection, movement type/date/quantity/reason and a detail panel.
- [x] **Step 4: Implement the guarded adjustment form**; require confirmation, submit a signed delta and reason, show an explicit negative-balance warning, and invalidate both query families.
- [x] **Step 5: Run focused tests, all frontend tests and build.**

### Task 5: Sales, account payments, and returns

**Files:**
- Modify: `frontend/src/app/App.tsx`
- Create: `frontend/src/features/sales/SalesPage.tsx`
- Create: `frontend/src/features/sales/SalesPage.test.tsx`
- Create: `frontend/src/features/payments/PaymentsPage.tsx`
- Create: `frontend/src/features/payments/PaymentsPage.test.tsx`

**Interfaces:**
- Read sales/payments from `/api/sales?page=0&size=20` and `/api/payments?page=0&size=20`; sale snapshots are available from the order detail query.
- Account payment: `POST /api/customers/{customerId}/account-payments` with `{ amount, method: 'CASH'|'BANK_TRANSFER', transferReference?, saleId? }`; null `saleId` uses FIFO, a concrete ID targets one debt; hide command unless `SALE_PAYMENT` or `ADMIN_ALL`.
- Returns use `POST /api/sales/{saleId}/returns` with `{ reason, items: [{ saleItemId, quantity }] }`, require `ADMIN_ALL`, and display the backend's stock/ledger result.

- [x] **Step 1: Add failing tests** for sales/payment listings, transfer reference, FIFO allocations, permission visibility, query invalidation and balance refresh. Sale-return form is blocked because there is no read API for sale lines.
- [x] **Step 2: Implement sales and payment tables** with server search, localized currency/date and first-page display.
- [x] **Step 3: Implement FIFO account payment form** with customer selection, permitted methods, optional transfer reference, response allocations and updated balance.
- [~] **Step 4: Partial return form blocked:** `saleItemId` is not available from any sale-detail read endpoint; document the required API contract in `frontend-backend-gaps.md`.
- [x] **Step 5: Run focused payments/sales tests, complete frontend suite and build.**

### Task 6: Delivery, cancellation, PDFs, and notifications

**Files:**
- Modify: `frontend/src/features/orders/OrderDetailPage.tsx`
- Modify: `frontend/src/features/orders/OrderDetailPage.test.tsx`
- Create: `frontend/src/features/orders/DeliveryDialog.tsx`
- Create: `frontend/src/features/orders/DeliveryDialog.test.tsx`
- Create: `frontend/src/features/orders/OrderDocuments.tsx`
- Create: `frontend/src/features/orders/OrderDocuments.test.tsx`

**Interfaces:**
- Delivery: `POST /api/orders/{orderId}/delivery-attempts` with result `DELIVERED|FAILED`, optional observation, optional `payments` of CASH/BANK_TRANSFER, and optional `transferReference`.
- Cancellation: `POST /api/orders/{orderId}/cancel`; only allowed state/payment combinations accepted by backend.
- Documents: GET `/api/orders/{orderId}/documents/a4` and `/documents/ticket` return PDF binary responses.
- Notification: POST `/api/orders/{orderId}/notifications` with `{ channel, recipient, format, idempotencyKey }`; query request state at `/notifications/{requestId}`.

- [x] **Step 1: Add tests** for delivered/failed constraints, required observation on failed delivery, optional transfer number, overpayment validation, cancellation, PDF download, notification E.164 validation and status states.
- [x] **Step 2: Implement delivery dialog** with conditional form fields, permission checks (`SALE_DELIVER`/`ADMIN_ALL`), submission feedback, and order/sales/payment query invalidation.
- [x] **Step 3: Implement cancellation confirmation** and handle 403/404/409 while preserving the current order detail.
- [x] **Step 4: Implement PDF downloads** by requesting authenticated binary content and using a temporary object URL with a meaningful filename.
- [x] **Step 5: Implement notification request and status view**; distinguish QUEUED, SENDING, SENT, FAILED and RETRY_EXHAUSTED and tell the user when a provider is not configured.
- [x] **Step 6: Run focused delivery/document tests, complete suite and build.**

### Task 7: User, seller, reassignment, and credit-limit administration

**Files:**
- Modify: `frontend/src/app/App.tsx`
- Create: `frontend/src/features/admin/UsersPage.tsx`
- Create: `frontend/src/features/admin/UsersPage.test.tsx`
- Create: `frontend/src/features/admin/SellersPage.tsx`
- Create: `frontend/src/features/admin/SellersPage.test.tsx`
- Create: `frontend/src/features/admin/CreditLimitPage.tsx`
- Create: `frontend/src/features/admin/CreditLimitPage.test.tsx`

**Interfaces:**
- Users read through `/api/users?page=0&size=20`; actions are POST `/api/users/invite`, `/{id}/block`, `/{id}/unblock`, and `/{id}/revoke-sessions`; activation is public POST `/api/auth/activate`.
- Sellers read through `/api/sellers?page=0&size=20&search=&status=` and `/api/sellers/{id}`; create uses `{ userId, displayName }`, update `{ displayName }`, status `{ status }`, customer reassignment `{ sourceSellerId, targetSellerId, customerIds?, reassignPendingOrders? }`, and order reassignment `{ targetSellerId, orderIds, onlyPending? }`.
- Credit limit uses GET/PUT `/api/settings/credit-limit` with `{ creditLimit }`; requires `ADMIN_ALL`.

- [x] **Step 1: Add failing user tests** for invitation/temporary password creation, one-time activation, block/unblock/session revoke confirmation, and permission visibility.
- [x] **Step 2: Implement users screen and `/activate?token=...` route**; submit `{ activationToken, password }` and expose the generated activation link for sharing.
- [~] **Step 3: Add seller tests** for list/create/edit/status and bulk customer reassignment; selected-order reassignment is implemented but has no dedicated interaction test.
- [x] **Step 4: Implement seller management** with user selection, `SellerDtos` requests, confirmed bulk reassignment and API result counts.
- [x] **Step 5: Add credit-limit tests and settings form** for enabled/disabled limits, non-negative scale-four input, saving feedback and backend warnings in order confirmation.
- [x] **Step 6: Run focused admin tests, full suite and build.**

### Task 8: Reconcile checklist and record unsupported features

**Files:**
- Modify: `docs/development/frontend-checklist.md`
- Modify: `docs/development/implementation-checklist.md`
- Create: `docs/development/frontend-backend-gaps.md`

- [x] **Step 1: Mark only verified criteria** `[x]` in frontend checklists and keep partial coverage `[~]` where appropriate.
- [x] **Step 2: Record unsupported user-facing features** in `docs/development/frontend-backend-gaps.md`.
- [x] **Step 3: Record operational follow-ups separately** in `docs/development/frontend-backend-gaps.md`.
- [x] **Step 4: Run `npm test -- --run`, `npm run build`, and `git diff --check`; inspect `git status --short` and summarize the verified and unsupported scope.**
