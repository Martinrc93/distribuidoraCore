# Customers And Products UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Completar la UI real de clientes y productos con asignaciones, edición, baja/reactivación, permisos y feedback verificable.

**Architecture:** Mantener las rutas y el `AppShell` actuales, extraer las páginas de clientes y productos desde `App.tsx` a componentes de módulo y reutilizar `apiPost`, `apiPut`, `apiPatch` y TanStack Query. Los formularios seguirán siendo controlados y el backend seguirá siendo la autoridad para autorización, unicidad y reglas de negocio.

**Tech Stack:** React 19, TypeScript, React Router, TanStack Query, Vitest, Testing Library, Vite.

## Global Constraints

- No agregar React Hook Form, Zod ni otra dependencia de formularios.
- No cambiar contratos backend existentes: `/api/customers`, `/api/products`, `POST`, `PUT` y `PATCH .../status`.
- No usar claims del JWT para autorizar requests; los permisos del frontend solo ocultan acciones y el backend decide.
- Las mutaciones deben invalidar la query de listado correspondiente.
- Los formularios deben mostrar carga, vacío, error y éxito y funcionar en desktop y mobile.
- No incluir `frontend/node_modules`, `frontend/dist`, `backend/target` ni otros artefactos generados.

---

### Task 1: HTTP mutations and permission helpers

**Files:**
- Modify: `frontend/src/shared/api/client.ts`
- Create: `frontend/src/shared/api/client.test.ts`
- Create: `frontend/src/shared/auth/permissions.ts`
- Create: `frontend/src/shared/auth/permissions.test.ts`

**Interfaces:**
- `apiPost<T>(path: string, body: unknown): Promise<T>`
- `apiPut<T>(path: string, body: unknown): Promise<T>`
- `apiPatch<T>(path: string, body: unknown): Promise<T>`
- `getAuthorities(): string[]` reads the JWT `authorities` claim and returns `[]` for missing or malformed tokens.
- `hasAuthority(authority: string): boolean` delegates to `getAuthorities()` and never replaces backend authorization.

- [ ] **Step 1: Write failing client tests**

Use mocked `fetch` to assert that `apiPost`, `apiPut` and `apiPatch` send JSON and the bearer token, that a `204` returns `undefined`, and that a `409` error exposes the API `detail`.

```ts
it('sends a bearer mutation and returns JSON', async () => {
  sessionStorage.setItem('distribuidora.accessToken', 'token')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"id":"1"}', { status: 200 })))

  await expect(apiPut('/api/customers/1', { businessName: 'Nuevo' })).resolves.toEqual({ id: '1' })
  expect(fetch).toHaveBeenCalledWith('/api/customers/1', expect.objectContaining({
    method: 'PUT',
    body: '{"businessName":"Nuevo"}',
    headers: expect.objectContaining({ Authorization: 'Bearer token' }),
  }))
})

it('returns undefined for 204 and exposes conflict detail', async () => {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(new Response('{"detail":"SKU duplicado"}', { status: 409 })))
  await expect(apiPatch('/api/products/1/status', { status: 'INACTIVE' })).resolves.toBeUndefined()
  await expect(apiPost('/api/products', {})).rejects.toThrow('SKU duplicado')
})
```

- [ ] **Step 2: Run the focused tests and verify they fail or expose missing coverage**

Run: `npm test -- --run src/shared/api/client.test.ts`

Expected: the new assertions fail until the test-compatible API behavior is implemented or corrected.

- [ ] **Step 3: Implement the authority decoder**

Decode only the JWT payload segment with `atob`, parse JSON, and return `payload.authorities` only when it is an array of strings. Do not validate or sign tokens in the browser.

- [ ] **Step 4: Add permission tests**

Cover an `ADMIN_ALL` token, a `CUSTOMER_WRITE` token, a token with no authorities, and malformed token input. Assert malformed input returns `[]` without throwing.

- [ ] **Step 5: Run focused tests**

Run: `npm test -- --run src/shared/api/client.test.ts src/shared/auth/permissions.test.ts`

Expected: all focused tests pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/shared/api/client.ts frontend/src/shared/api/client.test.ts frontend/src/shared/auth/permissions.ts frontend/src/shared/auth/permissions.test.ts
git commit -m "test: cover frontend mutation and permission helpers"
```

### Task 2: Customer management UI

**Files:**
- Create: `frontend/src/features/customers/CustomersPage.tsx`
- Create: `frontend/src/features/customers/CustomersPage.test.tsx`
- Modify: `frontend/src/app/App.tsx`
- Modify: `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java`
- Modify: `backend/src/main/java/com/distribuidora/dashboard/api/ReadQueryController.java`
- Create: `backend/src/test/java/com/distribuidora/dashboard/SellerReadQueryTest.java`

**Interfaces:**
- `CustomersPage` owns the `/api/customers?page=0&size=20` query and invalidates that exact query key after mutations.
- `CustomerForm` accepts `initial?: { id: string; name: string; taxId: string; sellerId?: string; priceListId?: string; status?: string }` and `onDone: () => void`.
- Customer create/update payloads are `{ businessName, taxId, sellerId?: string }`; price-list assignment uses a separate `PATCH /api/customers/{id}/price-list` call with `{ priceListId: string | null }`.
- Status payloads are `{ status: 'ACTIVE' | 'INACTIVE' }`.

- `GET /api/sellers?page=0&size=100` returns seller projections `{ id: string, displayName: string, email: string }` and is restricted to `ADMIN_ALL`.

- [ ] **Step 1: Extract the current customer page without changing behavior**

Move the current customer table and controlled form out of `App.tsx`; preserve the route, columns, existing query key and `apiPost`/`apiPut` calls. Export `CustomersPage` for direct tests.

- [ ] **Step 1a: Add the seller projection needed by the selector**

Add `ReadQueryService.sellers(int page, int size)` with a parameterized join between `seller.seller_profiles` and `identity.users`, returning only `id`, `displayName` and `email`. Add `GET /api/sellers` to `ReadQueryController` with `@PreAuthorize("hasAuthority('ADMIN_ALL')")`. Test the SQL parameters and page response with the existing JDBC mocking style before consuming it from React.

- [ ] **Step 2: Add seller and price-list fields**

Add a seller selector using the new admin-only `GET /api/sellers?page=0&size=100` projection backed by `seller.seller_profiles` joined to `identity.users`; reuse the existing `GET /api/pricing/lists?page=0&size=100` endpoint for price lists. Keep the current selections in edit mode, send seller assignment in the customer create/update payload, and send price-list assignment through its separate endpoint. Omitted optional values must be `undefined`, not empty placeholder labels.

- [ ] **Step 3: Add status actions and confirmation**

Add an action that calls `apiPatch('/api/customers/{id}/status', { status: nextStatus })` after `window.confirm` or an existing confirmation component. Show the action only when `hasAuthority('ADMIN_ALL')` is true; still display a backend `403` as a visible error.

- [ ] **Step 4: Add mutation feedback and empty state**

Show a temporary success message after create/update/status changes, keep the form open on errors, map `400`, `403`, `404` and `409` messages to actionable Spanish copy, and render a customer-specific empty state when `content` is empty.

- [ ] **Step 5: Write customer tests**

Mock the API module and verify create, edit, assignment payloads, status confirmation, successful invalidation, disabled submit while saving, seller action hiding, and conflict error rendering.

- [ ] **Step 6: Run focused tests**

Run from `frontend`: `npm test -- --run src/features/customers/CustomersPage.test.tsx`

Expected: all customer tests pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/customers/CustomersPage.tsx frontend/src/features/customers/CustomersPage.test.tsx frontend/src/app/App.tsx
git commit -m "feat: complete customer management UI"
```

### Task 3: Product management UI

**Files:**
- Create: `frontend/src/features/products/ProductsPage.tsx`
- Create: `frontend/src/features/products/ProductsPage.test.tsx`
- Modify: `frontend/src/app/App.tsx`

**Interfaces:**
- `ProductsPage` owns the `/api/products?page=0&size=20` query and invalidates that exact query key after mutations.
- `ProductForm` accepts `initial?: { id: string; sku: string; name: string; category: string; presentation: string; cost: number; price: number; status?: string }` and `onDone: () => void`.
- Product payloads are `{ sku, name, category, presentation, cost: number, price: number }`.

- [ ] **Step 1: Extract the current product page and form**

Move the current product table/form out of `App.tsx`; preserve real product reads, numeric conversion and query invalidation. Export `ProductsPage` for direct tests.

- [ ] **Step 2: Add edit mode**

Add an action column that opens `ProductForm` with all editable values and uses `apiPut('/api/products/{id}', payload)`. Do not send formatted currency strings to the API.

- [ ] **Step 3: Add local numeric validation**

Reject blank, non-numeric or negative `cost`/`price` before calling the API. Render field-level messages and preserve entered values. Keep server-side `409` messages for duplicate SKU.

- [ ] **Step 4: Add status actions and empty state**

Use `apiPatch('/api/products/{id}/status', { status: nextStatus })` after confirmation. Show status actions only for `ADMIN_ALL`, add a product-specific empty state, and show success/error feedback.

- [ ] **Step 5: Write product tests**

Mock the API module and verify create, edit, numeric payloads, negative-value rejection without a request, status confirmation, admin-only action visibility, query invalidation and duplicate-SKU errors.

- [ ] **Step 6: Run focused tests**

Run from `frontend`: `npm test -- --run src/features/products/ProductsPage.test.tsx`

Expected: all product tests pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/products/ProductsPage.tsx frontend/src/features/products/ProductsPage.test.tsx frontend/src/app/App.tsx
git commit -m "feat: complete product management UI"
```

### Task 4: Integration verification and checklist

**Files:**
- Modify: `docs/development/frontend-checklist.md`
- Modify: `frontend/src/app/App.test.tsx`

- [ ] **Step 1: Update app route tests**

Verify the `/customers` and `/products` routes render their extracted pages and that the existing authenticated shell/login behavior remains intact.

- [ ] **Step 2: Run the complete frontend test suite**

Run: `npm test -- --run`

Expected: all frontend tests pass.

- [ ] **Step 3: Run the TypeScript/Vite build**

Run: `npm run build`

Expected: TypeScript compilation and Vite production build pass.

- [ ] **Step 4: Review responsive and permission states**

Check desktop and mobile layouts for long forms, select controls, action columns and error messages. Check a seller token does not render admin-only status actions while a backend `403` remains visible.

- [ ] **Step 5: Mark only verified checklist items**

Update the Customers and Catalog sections to `[x]` only for implemented and tested items; leave pricing screen and unrelated order/inventory items unchanged.

- [ ] **Step 6: Commit the integration result**

```bash
git add frontend/src/app/App.test.tsx docs/development/frontend-checklist.md
git commit -m "test: verify customer and product UI integration"
```

- [ ] **Step 7: Final verification before push**

Run:

```bash
npm test -- --run
npm run build
git diff --check HEAD~4..HEAD
```

Expected: tests/build pass, no whitespace errors, and only source, test and documentation files appear in the new commits.
