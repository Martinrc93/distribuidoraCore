# Single inventory without depots — Design

## Goal

Remove depots as a business concept throughout the application. Every product has one stock balance, and order confirmation, returns, cancellations, edits, and manual adjustments change that balance directly.

## Approved business rules

- The business has one stock balance per product and no depot selection or depot administration.
- Order creation does not show the current explanatory subtitle or any depot field, and the confirmation API does not accept a depot identifier.
- Inventory is keyed by product only. A stock movement records the product and quantity delta, without a depot association.
- On migration, existing balances for a product are summed into its single balance. Existing movement and order/sale records remain, but their depot attribution is removed.
- Transfers between depots are no longer a supported operation.
- A retry that crosses the V24 deployment using a pre-migration confirmation key may receive `409` because its stored fingerprint included a depot. No duplicate order is created; new attempts keep normal exact-payload idempotency. The user explicitly accepted this narrow transition behavior so no depot compatibility data remains.

## Design

### Frontend

- Remove the order-creation subtitle and all depot loading/state/validation/payload logic from `OrderCreatePage`.
- Replace the depot-oriented inventory section with one searchable, paginated stock list. Remove depot selection, creation/status actions, transfer UI, and depot-specific wording.
- Manual stock adjustments send only quantity and reason. Inventory and movement views no longer display depot names or IDs.
- Update order, inventory, and access-control tests to exercise the single-stock contract.

### Backend

- Remove depot CRUD/balance APIs and transfer APIs/services. Keep inventory listing and adjustment APIs with no depot fields.
- Simplify inventory balance and movement commands to update one product balance and insert a movement without resolving or validating a depot.
- Remove depot identifiers from order confirmation commands/DTOs/fingerprints, order and sale persistence, detail projections, and dashboard inventory/movement projections.
- Update order edits, delivery cancellation, returns, and seed data to operate on the one product balance. Preserve transactional behavior and existing authorization rules for inventory adjustments and order workflows.
- Existing transfer movement rows remain historical movement records; the application no longer creates new transfers.

### Database migration

Add a forward-only Flyway migration after V23 that:

1. Replaces `inventory.inventory_balances` with a product-keyed balance whose quantity is the sum of all existing rows per product; the updated timestamp is the latest timestamp among that product's rows.
2. Removes `depot_id` from inventory balances, stock movements, orders, and sales, along with depot foreign keys and indexes.
3. Drops `inventory.depots` after its dependent data and constraints have been handled.
4. Preserves product quantities and movement/order/sale business records. Depot attribution is intentionally not retained.

Migration must be safe on the existing V23 schema and run transactionally under the repository's Flyway/PostgreSQL setup. No historical migration is edited.

## Error handling and behavior

- Stock adjustment validation remains unchanged except that there is no depot resolution.
- Existing stock locking and transaction boundaries continue to prevent lost updates.
- Sales and reversals update the same product balance, so a return or cancellation restores the product's single stock.
- The product stock exposed by APIs remains the direct balance, not a sum query across locations.

## Verification

- Backend unit and integration tests cover single-balance writes, summed migration data, order confirmation/edit, cancellation, returns, adjustments, and absence of depot contract fields/endpoints.
- Frontend tests cover the removed order subtitle/depot field and single-stock listing/adjustment flows.
- Run backend tests/package and frontend tests/build; validate Flyway migration against PostgreSQL and run the repository's relevant order/inventory smoke checks.
- Update backend/frontend documentation and smoke scripts that describe multi-depot inventory or transfers.

## Scope boundaries

- This change does not remove inventory movement history, order/sale history, or stock adjustment functionality.
- Existing transfer records are not deleted; they remain historical movements without location attribution.
- No depot compatibility mode or replacement location abstraction is introduced.
