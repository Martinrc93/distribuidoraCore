# Product Cost and Price Lists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the product-level price and make product cost edits require compatible prices for every affected active price list.

**Architecture:** Keep `catalog.product_prices` as the only source of sale prices. Add one transactional product update path that reads active list prices, validates any required replacement prices, and writes the cost plus affected list prices atomically. Keep independent price-list edits unchanged.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, Flyway, PostgreSQL, JUnit 5, React, TypeScript, Vitest, TanStack Query.

## Global Constraints

- The product has no general price and no cost history.
- Active price lists are the only lists used to validate a new product cost.
- A new price for every affected list must be greater than or equal to the new cost.
- Cost and replacement prices must commit together or roll back together.
- Product and price mutations remain restricted to `ADMIN_ALL`.
- Do not revert or include unrelated existing worktree changes.

---

### Task 1: Migrate the catalog schema and demo seed

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__move_product_prices_to_price_lists.sql`
- Modify: `backend/src/main/java/com/distribuidora/demo/DemoDataSeeder.java:78-86,132-149`
- Test: `backend/src/test/java/com/distribuidora/db/migration/ProductPriceOwnershipMigrationContractTest.java`
- Test: `backend/src/test/java/com/distribuidora/demo/DemoDataSeederTest.java`

**Interfaces:**
- Produces a database where `catalog.products` has `cost` but no `price`.
- Preserves existing prices by ensuring every existing product has a `catalog.product_prices` row before dropping the column.
- Changes demo product creation to insert cost first, then create list prices explicitly.

- [ ] **Step 1: Write the failing migration contract test**

Assert the migration text contains the backfill from `products.price` into
`product_prices`, drops the old column, and removes the old `price` check
constraint. Assert the seed test expects prices to be stored in
`catalog.product_prices`, not `products.price`.

- [ ] **Step 2: Run the focused migration and seed tests**

Run: `mvn -q -Dtest=ProductPriceOwnershipMigrationContractTest,DemoDataSeederTest test`

Expected: FAIL because `V11__move_product_prices_to_price_lists.sql` does not
exist and the seed still references `products.price`.

- [ ] **Step 3: Add the migration**

Create `V11__move_product_prices_to_price_lists.sql` with this order:

```sql
INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
SELECT pl.id, p.id, p.price, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM catalog.price_lists pl
JOIN catalog.products p ON TRUE
WHERE pl.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM catalog.product_prices pp
      WHERE pp.price_list_id = pl.id AND pp.product_id = p.id
  );

ALTER TABLE catalog.products DROP CONSTRAINT ck_product_amounts;
ALTER TABLE catalog.products DROP COLUMN price;
ALTER TABLE catalog.products
    ADD CONSTRAINT ck_product_cost CHECK (cost >= 0);
```

- [ ] **Step 4: Update the demo seed**

Change `insertProducts()` to insert only `cost`, then insert each active list
price using the generated product ID and a deterministic multiplier. Remove the
`ensureProductPrices()` query that reads `products.price`; replace it with an
idempotent query that fills missing list prices from `products.cost` using the
same seed rule or leave all rows created by `insertProducts()` present before
calling the repair method.

- [ ] **Step 5: Run the focused tests again**

Run: `mvn -q -Dtest=ProductPriceOwnershipMigrationContractTest,DemoDataSeederTest test`

Expected: PASS.

### Task 2: Implement transactional product cost updates

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/catalog/application/ProductCommandService.java:26-63`
- Modify: `backend/src/main/java/com/distribuidora/catalog/api/ProductCommandController.java:21-43`
- Modify: `backend/src/main/java/com/distribuidora/shared/error/ApiExceptionHandler.java:23-124`
- Test: `backend/src/test/java/com/distribuidora/catalog/ProductCommandServiceTest.java`
- Test: `backend/src/test/java/com/distribuidora/shared/error/ApiExceptionHandlerTest.java`
- Test: `backend/src/test/java/com/distribuidora/catalog/ProductCommandControllerTest.java` if present, otherwise create it beside the service test

**Interfaces:**
- Replace `ProductInput(..., BigDecimal cost, BigDecimal price)` with
  `ProductInput(..., BigDecimal cost, List<ProductPriceInput> prices)`.
- Add `ProductPriceInput(UUID priceListId, BigDecimal price)`.
- `create(ProductInput)` validates cost and all initial list prices.
- `update(UUID id, ProductInput)` reads active prices and atomically updates the
  product and supplied affected prices.

- [ ] **Step 1: Write failing service tests**

Add tests for these exact cases:

```java
void acceptsCostOnlyWhenNoActiveListPriceIsBelowIt()
void rejectsCostWhenAffectedListPriceIsMissing()
void rejectsAffectedPriceBelowNewCost()
void updatesCostAndAllAffectedPricesTogether()
void doesNotRequireUnaffectedListPrices()
void validatesInitialPricesAgainstCost()
```

Mock JDBC queries for active list prices and verify that the update SQL writes
`cost` and affected `product_prices` only after validation.

- [ ] **Step 2: Run the focused service tests to verify failure**

Run: `mvn -q -Dtest=ProductCommandServiceTest test`

Expected: FAIL because `ProductInput` still requires a product-level price and
the update path does not query list prices.

- [ ] **Step 3: Implement the input and validation types**

Use a list payload, reject duplicate `priceListId` values, reject null or
negative prices, and reject a null/negative cost. Keep text and SKU validation
unchanged.

- [ ] **Step 4: Implement the update decision tree**

Query active list prices with list ID and code. Compute affected lists where
`newCost.compareTo(currentPrice) > 0`. If affected lists exist, require exactly
one replacement entry for each affected list and require each replacement price
to be `>= newCost`. Update `catalog.products.cost` and then each replacement
`catalog.product_prices` row in the existing transaction. Return a structured
validation exception containing affected list codes when prices are missing. The
HTTP error body must retain the existing error envelope and add an
`affectedPriceLists` array with `{ priceListId, code, currentPrice }` objects.

- [ ] **Step 5: Update the controller payload**

Remove `price` from `ProductPayload` and expose `prices` as an optional list of
`{ priceListId, price }`. Preserve `@Valid`, decimal constraints, and
`ADMIN_ALL` authorization.

- [ ] **Step 6: Expose affected-list metadata in the error envelope**

Add a dedicated `ProductPriceValidationException` carrying the affected list
records. In `ApiExceptionHandler`, map it to `400` using `ProblemDetail` with
`code = "INVALID_PRODUCT_PRICES"` and an `affectedPriceLists` property. Add a
controller/error-handler test that verifies the property is serialized.

- [ ] **Step 7: Run the focused backend tests**

Run: `mvn -q -Dtest=ProductCommandServiceTest,ProductCommandControllerTest,ApiExceptionHandlerTest test`

Expected: PASS.

### Task 3: Remove product-level price from read APIs and dependent seed/query code

**Files:**
- Modify: `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java:71-90`
- Modify: `backend/src/main/java/com/distribuidora/demo/DemoDataSeeder.java:78-86,132-149`
- Modify: any catalog/dashboard response tests that assert `price` on `/api/products`
- Test: `backend/src/test/java/com/distribuidora/dashboard/ReadQueryServiceTest.java`
- Test: `backend/src/test/java/com/distribuidora/db/migration/PricingMigrationContractTest.java` if assertions reference the product column

**Interfaces:**
- Product list responses expose `id`, `sku`, `name`, `category`, `presentation`,
  `cost` only to admins, `stock`, and `status`.
- Seller-scoped product responses continue hiding `cost`, and neither audience
  receives product-level `price`.
- Order pricing continues reading `catalog.product_prices` through
  `PricingQueryService`.

- [ ] **Step 1: Update failing read tests**

Change assertions to require no `price` key and to preserve admin cost versus
seller cost visibility.

- [ ] **Step 2: Run the focused read tests**

Run: `mvn -q -Dtest=ReadQueryServiceTest,PricingQueryServiceTest test`

Expected: FAIL against the current `p.price` SQL projections.

- [ ] **Step 3: Remove `p.price` from product queries**

Delete the product-level price projection in both seller and admin branches;
leave stock, status, ownership, search, pagination, and cost visibility
unchanged.

- [ ] **Step 4: Verify order price resolution remains list-based**

Run: `mvn -q -Dtest=PricingQueryServiceTest,PricingCommandServiceTest test`

Expected: PASS with prices coming from `catalog.product_prices`.

### Task 4: Update the products UI and application consumers

**Files:**
- Modify: `frontend/src/features/products/ProductsPage.tsx:12-100,134-146`
- Modify: `frontend/src/features/products/ProductsPage.test.tsx`
- Modify: `frontend/src/app/App.tsx:222-223`
- Modify: any shared product type or API fixture that includes `price`

**Interfaces:**
- `Product` contains no `price` property.
- Product form sends `{ sku, name, category, presentation, cost, prices? }`.
- Product table shows cost, stock, and status but no “Lista general” column.
- Product create/edit forms use `GET /api/pricing/lists?page=0&size=20` to load
  active lists and `GET /api/pricing/lists/{listId}/prices` when existing
  product-list values are needed.

- [ ] **Step 1: Write failing frontend tests**

Update fixtures and add tests that assert:

```text
the product form sends no product-level price
the table does not render “Lista general”
cost-only edits send no prices when valid
the API validation response renders affected list fields
the completed edit sends priceListId/price entries with the cost
```

- [ ] **Step 2: Run focused frontend tests**

Run: `npm test -- --run src/features/products/ProductsPage.test.tsx`

Expected: FAIL until the product type, form, and table are updated.

- [ ] **Step 3: Remove product-level price from the form and table**

Delete `Product.price`, the product form `price` field, its local validation,
the table column, and all product-level price fixtures.

- [ ] **Step 4: Add affected-list price editing**

When the update returns `400` with affected list metadata, fetch or use the
current list-price data, render only affected list inputs, and retry the same
`PUT /api/products/{id}` with `prices: [{ priceListId, price }]`. Keep the normal
cost-only path unchanged.

- [ ] **Step 5: Add initial list prices to product creation**

Load active lists for the create form and render one initial price field per
active list. Submit those values in `prices`; reject blank, negative, or
below-cost values before making the request. Keep edit forms limited to the
affected lists returned by the API.

- [ ] **Step 6: Update app consumers**

Remove `product.price` from dashboard/demo UI consumers. Where a sale preview
needs a price, obtain it from the selected customer price list endpoint rather
than from `/api/products`.

- [ ] **Step 7: Run frontend verification**

Run: `npm test -- --run`

Expected: PASS with no product-level price assumptions.

### Task 5: Align documentation and remove cost-history references

**Files:**
- Modify: `docs/domain/functionalities.md`
- Modify: `docs/domain/domain-overview.md`
- Modify: `docs/architecture/data-architecture.md`
- Modify: `docs/diagrams/catalog/flow-create-product.md`
- Modify: `docs/diagrams/catalog/flow-price-resolution.md`
- Modify: `docs/diagrams/future/README.md`
- Delete: `docs/diagrams/future/flow-cost-history.md`
- Modify: `docs/superpowers/specs/2026-09-18-real-data-and-demo-seed-design.md`
- Modify: `docs/superpowers/specs/2026-09-18-pricing-design.md` where it says the default list is `GENERAL`

**Interfaces:**
- Documentation describes `catalog.product_prices` as the only sale-price
  source.
- Product flows show cost-only edits and the affected-list price requirement.
- No documentation promises cost history or a product-level general price.

- [ ] **Step 1: Search for stale terminology**

Run: `rg -n "precio general|historial de costos|products\.price|p\.price|price general|cost history" docs backend frontend --glob '!**/target/**' --glob '!**/node_modules/**'`

- [ ] **Step 2: Update diagrams and domain rules**

Replace stale product-price references with per-list prices and document the
transactional cost-update rule.

- [ ] **Step 3: Remove the obsolete cost-history diagram**

Delete the future cost-history flow and its README entry.

- [ ] **Step 4: Verify documentation consistency**

Run the search again and confirm remaining `price` references are only for
`catalog.product_prices`, order unit prices, or payment amounts.

### Task 6: Run the complete verification suite

**Files:**
- No source changes expected unless verification exposes a regression.

- [ ] **Step 1: Run backend tests**

Run: `mvn -q test` from `backend`.

Expected: PASS.

- [ ] **Step 2: Run frontend tests and build**

Run: `npm test -- --run` and `npm run build` from `frontend`.

Expected: PASS.

- [ ] **Step 3: Check formatting and stale references**

Run: `git diff --check` and the terminology search from Task 5.

Expected: no whitespace errors and no stale product-level price or cost-history
references in active documentation/code.

- [ ] **Step 4: Review only intended files**

Run: `git status --short` and `git diff --stat`. Do not revert or stage the
pre-existing unrelated worktree changes.
