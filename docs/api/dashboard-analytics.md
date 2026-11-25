# Operational dashboard analytics

`GET /api/dashboard/analytics?dateMin=2026-09-01&dateMax=2026-09-30`

Requires `ADMIN_ALL`. Dates use ISO format and inclusive business days in
`America/Argentina/Buenos_Aires`. Defaults to the current month through today.
Invalid, reversed, future or longer than 366-day ranges return `400`.
The report is read in a single transaction with repeatable-read isolation.

## Period metrics

- `sales`: amount and count of sales currently `CONFIRMED` or `DELIVERED`, by
  sale creation date. Totals already include discounts. Cancelled sales are excluded.
- `collections`: actual `CASH` and `BANK_TRANSFER` receipts by payment date,
  including receipts against older sales. Account debits are not receipts.
- `expenses`: total amount (`amount`), number of expenses (`count`), and the five
  most recent entries (`recent`) by registration time in the selected period.
  Each entry includes `id`, `amount`, `type`, optional `observation`, and `createdAt`.
- `previousSales` and `previousCollections`: the immediately preceding interval
  of equal length. If the current interval ends today, both stop at the same
  elapsed time on their last day. A zero comparison baseline has no percentage.
- `trend`: one point per business day, including zero-sales days.
- `topProducts`: five products ranked by gross units sold, excluding cancelled
  sales. Revenue allocates the order discount proportionally to line totals;
  tiny allocation rounding differences may remain. Merchandise returns do not
  imply financial credits or reduce this revenue metric.
  The Dashboard links to [Historial de artículos vendidos](sold-products.md)
  with the selected period for product totals, returns and individual sales.
- `sellers`: five highest revenue totals, using the seller on the order.
  Missing sellers remain in a separate unassigned group.
- `recentOrders`: five newest orders created in the interval, including cancelled
  orders so their status remains visible.

## Current priorities

These are current balances, not historical balances at the selected date:

- `current`: positive customer debt, debtor count, confirmed orders awaiting
  delivery, their value and oldest creation time, pending orders whose latest
  delivery attempt failed, and the number of active products with stock <= 0.
- `topDebtors`: five highest positive customer balances, including inactive customers.
  The dashboard shows each balance's share of `current.debt`, plus the total
  amount and share concentrated among those customers. These are current balances,
  independent of the selected analysis period. Links open pending sales for the
  customer or the full customer list filtered to debtors.
- `debtAging`: current open account debt grouped by sale age (0–30, 31–60,
  61–90 and over 90 days). Each sale is capped at the smaller of its positive
  ledger balance and `total - paid`. This is age, not overdue debt.
- `stockAlerts`: up to eight active products with zero or negative stock,
  most negative first. Products without an inventory balance count as zero.
- `stockCoverage`: eight active products with the shortest estimated coverage.
  Daily demand is units from active sales created during the last 30 complete
  business days, less their recorded merchandise returns, divided by 30.
  Coverage is nonnegative current stock / daily demand; no sales returns `null`.
  This is a demand estimate, not a purchasing recommendation or a stock minimum.
- `pendingOrders`: five oldest confirmed orders, regardless of selected period.

`generatedAt` is the UTC instant used for report cutoffs and current-date calculations.
All amounts are ARS. Empty sums return zero and empty rankings return empty lists.
Historic sales remain mutable when confirmed orders are edited; comparisons
therefore represent their current recorded values, not immutable accounting closes.

## Frontend

The admin-only `/analytics` route appears as **Dashboard**, immediately below
**Resumen**. The summary route `/dashboard` remains independent. Presets are
today, Monday-through-today, month-through-today, and custom dates. Date values
persist in the URL in `dd/mm/yyyy` format, using the existing calendar control.
The sales graph also exposes a keyboard-accessible data table. Retry, refresh,
empty states and document widths from 320px are covered by frontend tests.

Historical margin and overdue debt require cost-at-sale snapshots and payment
due dates respectively; neither is calculated from missing data.

Los revendedores aparecen como deudores con el saldo de su cuenta, que incluye los
pedidos de sus clientes. «Ver deuda» abre Ventas con `accountCustomerId`, que filtra
por la cuenta que debe cada venta (Fase 9.2).

## Recording expenses

`GET /api/expenses?page=0&size=20` returns the paginated expense history,
newest first. The optional `type`, `dateMin` and `dateMax` filters apply before
counting and pagination. Dates use `yyyy-mm-dd` and include the whole selected
day in `America/Argentina/Buenos_Aires`. Either date can be omitted.
`POST /api/expenses` creates an expense and returns it with status
`201`. Both endpoints require `ADMIN_ALL`.

Send a unique UUID `idempotencyKey`, a positive ARS `amount` with up to two
decimal places, and `type` (`PETTY_CASH`, `OTHER`, `SERVICES`, or `FUEL`).
`observation` is optional and accepts up to 500 characters. The server records
the registration time and administrator. Reusing the same key with the same
payload returns the original entry. Reusing it for a different expense returns `409`.
