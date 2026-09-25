# Backend Workflows Frontend Extension Implementation Plan

> **For agentic workers:** Execute inline task-by-task with the executing-plans skill. Each task uses TDD and completes its test cycle before moving on.

**Goal:** Expose backend-supported price scheduling/history, commercial discount rules, depot workflows and read-only user roles in the existing frontend modules.

**Architecture:** Extend current domain pages and extract focused sections where a page would otherwise own unrelated forms. Use the shared authenticated API client and TanStack Query cache; let backend responses remain authoritative for discount results, stock validation, permission checks, and idempotency.

**Tech Stack:** React 19, TypeScript, React Router 7, TanStack Query 5, Vitest, React Testing Library.

## Global Constraints

- Use the already approved visual direction and existing module navigation.
- Mutations of price schedules, discount rules and depot administration require `ADMIN_ALL`; transfers and adjustments require `STOCK_ADJUST`.
- Depot selection is available only to callers allowed to read `GET /api/inventory/depots`; otherwise omit `depotId` so the backend uses `CENTRAL`.
- Preserve the exact depot ID in an order's existing idempotency attempt and retry payload.
- Never display all-depot aggregate stock as a selected-depot balance, or calculate automatic discount outcomes without a backend preview endpoint.
- User roles are read-only in this scope. Defer role reassignment and role-permission editing.
- Do not implement sale returns until a read projection supplies actual sale-item IDs.
- Run focused tests, then the full frontend suite and build; restore or remove generated tracked build/test outputs afterward.

---

### Task 1: Effective Price Scheduling and History

**Files:**
- Modify: `frontend/src/features/pricing/PriceListsPage.tsx`
- Modify: `frontend/src/features/pricing/PriceListsPage.test.tsx`
- Verify contract: `docs/api/pricing.md`

**Interfaces:**
- `ProductPrice` gains `effectiveOn?: string`.
- `GET /api/pricing/lists/{listId}/products/{productId}/history?page=0&size=20` returns paginated rows with `effectiveOn`, `recordedAt`, `updatedAt`, and `scheduled`.
- Save sends `{ price: number, effectiveOn?: string }`; cancel calls `DELETE /api/pricing/lists/{listId}/products/{productId}/history/{effectiveOn}`.

- [x] **Step 1: Add failing tests** for submitting a future effective date, opening a product's history, displaying scheduled entries, cancelling a future entry only after confirmation, and invalidating list/history queries after success.
- [x] **Step 2: Run the focused test** with `npm test -- src/features/pricing/PriceListsPage.test.tsx`; verify failures are caused by absent date/history controls, not test setup.
- [x] **Step 3: Implement the minimal UI**: extend the edit form with an optional date input; display current `effectiveOn`; add a history panel/modal for the selected product; show the backend `scheduled` value; show cancel only for scheduled entries and require confirmation. Use existing `apiPut`, `apiDelete`, query keys and shared panels/buttons.
- [x] **Step 4: Re-run the focused test** and confirm all existing and new price-list tests pass.

### Task 2: Commercial Discount Rule Administration

**Files:**
- Create: `frontend/src/features/pricing/DiscountRulesSection.tsx`
- Create: `frontend/src/features/pricing/DiscountRulesSection.test.tsx`
- Modify: `frontend/src/features/pricing/PriceListsPage.tsx`
- Modify: `frontend/src/features/pricing/PriceListsPage.test.tsx`
- Verify contract: `docs/api/pricing.md`

**Interfaces:**
- Rules query: `GET /api/pricing/discount-rules?page=0&size=20`.
- Create: `POST /api/pricing/discount-rules`; update: `PUT /api/pricing/discount-rules/{id}`; status: `PATCH /api/pricing/discount-rules/{id}/status` with `{ status: 'ACTIVE' | 'INACTIVE' }`.
- Rule payload fields: `code`, `description`, `kind`, `percent`, nullable `customerId`, `priceListId`, `productId`, nullable `validFrom`, `validUntil`, and `priority`.

- [x] **Step 1: Add failing tests** for loading rules, creating a LINE rule with selected customer/list/product, rejecting ORDER rules with a product and LINE rules without one, editing a rule, changing status, and hiding mutations without `ADMIN_ALL`.
- [x] **Step 2: Run the focused test** with `npm test -- src/features/pricing/DiscountRulesSection.test.tsx`; confirm it fails because the section is not implemented.
- [x] **Step 3: Implement a focused section** with table, create/edit form and status confirmation. Load picker options from existing customers, active price lists and active products APIs, using page size 100 and current list contracts. Apply backend validations in UI: valid rule codes, percent `(0, 100]`, LINE requires product, ORDER forbids product, priority `[-1000, 1000]`, and inclusive date range. Do not add physical delete or client-side automatic discount calculation.
- [x] **Step 4: Mount the section** in `PriceListsPage`, retain existing prices view, and show that rules are applied by the server at order confirmation.
- [x] **Step 5: Run focused pricing tests** with `npm test -- src/features/pricing/PriceListsPage.test.tsx src/features/pricing/DiscountRulesSection.test.tsx`; fix any contract/type mismatch before continuing.

### Task 3: Depot Balances, Administration, Adjustments and Transfers

**Files:**
- Create: `frontend/src/features/inventory/DepotInventorySection.tsx`
- Create: `frontend/src/features/inventory/DepotInventorySection.test.tsx`
- Modify: `frontend/src/features/inventory/InventoryPage.tsx`
- Modify: `frontend/src/features/inventory/InventoryPage.test.tsx`
- Verify contract: `docs/api/inventory.md`

**Interfaces:**
- Depot list: `GET /api/inventory/depots` returns `{ id, code, name, status, isDefault }[]`.
- Create: `POST /api/inventory/depots` with `{ code, name }`.
- Status: `PATCH /api/inventory/depots/{depotId}/status` with `{ active: boolean }`.
- Balances: `GET /api/inventory/depots/{depotId}/balances?page=0&size=20&search=` returning rows `{ productId, sku, product, stock, updated }`.
- Transfer: `POST /api/inventory/transfers` with `{ fromDepotId, toDepotId, productId, quantity, reason }`.
- Adjustment remains `POST /api/inventory/{productId}/adjustments` with `{ depotId, quantity, reason }`.

- [x] **Step 1: Add failing tests** for depot listing/selection, per-depot balances, admin-only depot create/status controls, default depot deactivation prevention, adjustment payload including depot ID, and a transfer payload from a visible source balance to a different destination.
- [x] **Step 2: Run focused tests** with `npm test -- src/features/inventory/InventoryPage.test.tsx src/features/inventory/DepotInventorySection.test.tsx`; verify missing behaviors fail as expected.
- [x] **Step 3: Implement the depot section**: keep the existing aggregate inventory panel; add a depot selector and paginated/searchable balance panel, depot create/status controls, and product-row transfer action. Show source balance and require a positive multiple of 0.5 no greater than that displayed balance, a different active destination, and a reason. Confirm transfer/deactivation and invalidate aggregate, balance and movement query keys after success.
- [x] **Step 4: Integrate adjustments** so the adjustment dialog includes the selected `depotId` when one is selected; retain negative-balance warning and `STOCK_ADJUST` checks.
- [x] **Step 5: Run focused inventory tests** and confirm existing aggregate/movement/adjustment tests still pass.

### Task 4: Depot Selection in Order Confirmation

**Files:**
- Modify: `frontend/src/features/orders/OrderCreatePage.tsx`
- Modify: `frontend/src/features/orders/OrderCreatePage.test.tsx`
- Verify contract: `docs/api/inventory.md`

**Interfaces:**
- Extend `ConfirmationRequest` with optional `depotId?: string`.
- Load active depots only when the current user has `ADMIN_ALL`; default selection to the backend's `isDefault` depot.
- Non-admin payload omits `depotId`.

- [x] **Step 1: Add failing tests** for authorized depot loading/selection and request payload, fallback omission for a non-admin, retaining the selected depot in an exact retry after failure, and generating a new attempt when the selected depot changes.
- [x] **Step 2: Run** `npm test -- src/features/orders/OrderCreatePage.test.tsx`; confirm the new selected-depot assertions fail before implementation.
- [x] **Step 3: Implement controlled depot selection** for admins. On changing it, call the same draft invalidation used for customer/list/line changes; include selected `depotId` in `buildPayload` so the stored `attempt` preserves it across retry. Label existing product stock as aggregate, not depot-specific.
- [x] **Step 4: Re-run the focused order test** and verify retry key and full payload stability, including depot ID.

### Task 5: Read-Only User Role Display and Documentation

**Files:**
- Modify: `frontend/src/features/admin/UsersPage.tsx`
- Modify: `frontend/src/features/admin/UsersPage.test.tsx`
- Modify: `docs/development/frontend-checklist.md`
- Modify: `docs/development/implementation-checklist.md`
- Modify: `docs/development/frontend-backend-gaps.md`

**Interfaces:**
- User row includes `roles: string` from `GET /api/users`; render known role codes readably and preserve unknown codes safely.
- No role or permission mutation is introduced.

- [x] **Step 1: Add a failing test** that supplies roles in the user response and verifies the role is rendered without exposing change-role/permission-edit actions.
- [x] **Step 2: Run** `npm test -- src/features/admin/UsersPage.test.tsx`; verify the role visibility assertion fails because the table currently omits role.
- [x] **Step 3: Implement read-only role rendering** with accessible label text; preserve all existing user actions.
- [x] **Step 4: Update checklists/gap documentation**: mark supported pricing/depot/role-display features complete; record sale return, customer statement, delivery history, non-admin depot access, and deferred role/permission edits accurately.
- [x] **Step 5: Run** `npm test -- src/features/admin/UsersPage.test.tsx` and confirm all user tests pass.

### Task 6: Full Frontend Verification and Artifact Cleanup

**Files:**
- Verify: all changed frontend modules and tests.
- Clean generated output only: `frontend/dist`, `frontend/node_modules/.tmp/tsconfig.app.tsbuildinfo`, `frontend/node_modules/.vite/vitest/*/results.json`.

- [x] **Step 1: Run the full frontend suite** with `npm test` from `frontend`; expect all tests to pass.
- [x] **Step 2: Run the production build** with `npm run build` from `frontend`; expect TypeScript and Vite build success.
- [x] **Step 3: Inspect `git status`** and restore/remove only generated outputs listed above; preserve all source, docs and tests.
- [x] **Step 4: Verify final diff** with `git diff --check` and confirm no conflict markers or generated artifact changes remain.

## Plan Self-Review

- Pricing schedule/history/cancellation and discount rule lifecycle are covered by Tasks 1–2.
- Depot balances/admin/adjustment/transfer and order depot/idempotency are covered by Tasks 3–4.
- Read-only user roles and explicitly unresolved backend gaps are covered by Task 5.
- Error/loading/empty/success states, authority visibility and cache invalidation are required in the focused module tasks.
- Full tests/build and tracked generated-file cleanup are covered by Task 6.
- Role mutations, permission editing and sale-return UI remain out of scope as approved.
