# Backend Workflows Frontend Extension Design

## Goal

Expose the newly supported backend workflows in the existing frontend modules,
preserving the approved distribution-ledger visual direction and the rule that
the backend remains authoritative for business decisions. Implement the work in
three increments: pricing, inventory/order depots, then read-only role display.

## Approved Scope

1. **Pricing and discounts**
   - Schedule product/list prices with `effectiveOn`.
   - Review a product/list price history and cancel future entries.
   - Create, edit, activate/deactivate, and list commercial discount rules.
   - Restrict mutations to `ADMIN_ALL`.
2. **Inventory depots and orders**
   - Show total inventory and per-depot balances.
   - Create and activate/deactivate depots for `ADMIN_ALL`.
   - Adjust stock in a selected depot and transfer stock between depots with
     origin balance, quantity and reason visible before submission.
   - Select a depot during order confirmation only where the caller can read
     depots; preserve that choice in the exact idempotent retry payload.
3. **User role visibility**
   - Show the role returned by the users API in the existing user list.
   - Role changes and role-permission editing are explicitly deferred.

The frontend will not add sale returns in this cycle. Although the backend
accepts return commands, the order detail projection does not expose the required
sale-item IDs. The gap will remain documented until the backend supplies a safe
read model. Other gaps such as customer statements and delivery-attempt history
remain documented separately.

## Existing Architecture and Placement

Extend existing feature modules instead of adding a combined settings center or
new top-level feature routes:

- `PriceListsPage` gains price-history/scheduling and discount-rule sections.
- `InventoryPage` gains depot selection, balances, administration, adjustments
  and transfers.
- `OrderCreatePage` gains the authorized depot selector.
- `UsersPage` displays returned role codes as read-only information.

Use the current React Router, TanStack Query, shared authenticated API helpers,
and shared `PageHeader`, `Panel`, `DataTable`, `Button`, and `EmptyState`
components. Keep each domain's query keys and UI state close to its feature.

## Pricing and Discount Behavior

The existing price edit dialog will accept an optional effective date. An empty
date means the backend's current commercial date; future dates are visibly
identified as scheduled. Each product/list row will expose its current effective
date and an action to inspect paginated history. Future history entries can be
cancelled with explicit confirmation; current and past entries are read-only.
Successful writes invalidate the affected list-price and history queries.

Discount rules will be managed in a distinct section of the pricing module.
The form will represent the backend contract: code, description, `LINE` or
`ORDER`, percent, optional customer/list scope, product for `LINE` rules,
inclusive validity dates, and priority. The table will show scope, status and
validity and support edit and active-state changes. No physical-delete action is
offered. Administrative mutations require `ADMIN_ALL`; the backend resolves
priority, specificity and snapshots during order confirmation/edit.

Do not calculate or imply the result of automatic rules in the browser because
there is no preview endpoint. Continue to show the final totals returned by the
backend and state that automatic discounts are applied when the order is
confirmed. Existing manual discount and price override controls remain
administrator-only.

## Depot and Order Behavior

Inventory keeps the existing all-depot aggregate view and adds a depot selector
backed by `GET /api/inventory/depots`. Per-depot balances come from the selected
depot balances endpoint. The selected depot is clearly labelled so aggregate
stock is never mistaken for a depot balance. Depot creation and status changes
are available only to `ADMIN_ALL`; the default depot cannot be deactivated.

Manual adjustments include the selected `depotId`. Transfers require distinct
active source and destination depots, a positive half-unit multiple, a product,
and a reason. Display the source balance and reject a quantity larger than that
visible balance before sending; backend transaction/locking remains the final
authority. Confirm transfer submission and invalidate aggregate inventory,
per-depot balance and movement queries on success. Transfer controls require
`STOCK_ADJUST`, while depot reads/administration follow the documented
`ADMIN_ALL` restriction.

For order confirmation, callers allowed to read the depot list can select an
active depot. The selected ID is included in the confirmation payload and stored
attempt object, so retries reuse the same depot, key, and payload. Changing any
order field invalidates the previous attempt as it does today. For callers who
cannot read depots, omit `depotId` and rely on the backend's `CENTRAL` default.
Do not show aggregate product stock as if it were the selected depot's available
stock; the backend validates availability at confirmation.

## User Roles

The existing user list will render the API's `roles` value read-only, using
friendly labels for known role codes and retaining an accessible fallback for
unknown values. Keep current invite, user-management, blocking and session
actions unchanged. Do not expose role reassignment or role-permission editing
until the user requests that scope.

## Shared UX, Errors, and Authorization

All added queries and forms include loading, error, empty, saving, success and
retry/feedback states consistent with nearby pages. Preserve backend error
details where actionable and provide specific guidance for `403`, `404`, and
`409` conflicts. Irreversible actions (scheduled price cancellation, depot
deactivation, transfer) require explicit confirmation. Client-side visibility is
only a usability measure; server authorization remains authoritative.

## Verification

Add focused Vitest coverage per increment for:

- Request paths, payload fields, query invalidation, status changes and future
  price cancellation.
- Discount rule create/edit/activation, optional scopes, protected authority
  visibility and validation errors.
- Depot selection and balances, adjustment/transfer payloads, insufficient
  source balance, confirmations, authorization, and query refresh.
- Order payload includes selected `depotId`; failed retries reuse the exact same
  key and payload, including depot; changing a draft field creates a new attempt.
- User role is rendered read-only from the API response.

Run the focused frontend tests, full frontend suite and production build after
each increment. Re-run backend tests only if API assumptions or backend code
change. Clean generated artifacts after verification because build outputs are
tracked in this repository.

## Unresolved Gaps to Keep Documented

- Sale-return UI needs sale-item IDs in a read projection.
- Customer statements and customer-specific debt selection need dedicated
  customer read endpoints.
- Delivery-attempt history needs a read projection.
- Role reassignment and permission editing are intentionally deferred by the
  user, despite backend endpoint support.
- Non-admin order creators cannot select depots until the backend exposes an
  authorized depot-list read to those roles.
