import type { DashboardData } from './DashboardPage'
import type { DashboardReport } from './AnalyticsDashboardPage'

type Validator = (value: unknown) => boolean
const numeric: Validator = (value) => typeof value === 'number' && Number.isFinite(value)
const text: Validator = (value) => typeof value === 'string'
const timestamp: Validator = (value) => typeof value === 'string' && Number.isFinite(Date.parse(value))
const isoDate: Validator = (value) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
  && timestamp(value) && new Date(value).toISOString().slice(0, 10) === value
const nullable = (validate: Validator): Validator => (value) => value === null || validate(value)
const list = (validate: Validator): Validator => (value) => Array.isArray(value) && value.every(validate)
const fields = (schema: Record<string, Validator>): Validator => (value) => typeof value === 'object'
  && value !== null && !Array.isArray(value)
  && Object.entries(schema).every(([key, validate]) => validate((value as Record<string, unknown>)[key]))

const metrics = { performedOrders: numeric, deliveredOrders: numeric, totalBilled: numeric, totalPaid: numeric, accountBalance: numeric }
const summary = fields({
  dateMin: isoDate, dateMax: isoDate, totals: fields(metrics),
  bySeller: list(fields({ ...metrics, sellerId: nullable(text), seller: text })),
})
const sales = fields({ amount: numeric, orders: numeric })
const collections = fields({ amount: numeric, cash: numeric, transfer: numeric })
const order = fields({ id: text, number: text, customer: text, seller: text, total: numeric, status: text, date: timestamp })
const report = fields({
  dateMin: isoDate, dateMax: isoDate, previousDateMin: isoDate, previousDateMax: isoDate, generatedAt: timestamp,
  sales, previousSales: sales, collections, previousCollections: collections,
  current: fields({ debt: numeric, debtorCount: numeric, pendingOrders: numeric, pendingAmount: numeric,
    oldestPendingAt: nullable(timestamp), failedDeliveries: numeric, stockAlertCount: numeric }),
  trend: list(fields({ date: isoDate, amount: numeric, orders: numeric })),
  topDebtors: list(fields({ id: text, name: text, balance: numeric })),
  debtAging: fields({ days0to30: numeric, days31to60: numeric, days61to90: numeric, daysOver90: numeric }),
  stockAlerts: list(fields({ id: text, name: text, stock: numeric })),
  stockCoverage: list(fields({ id: text, name: text, stock: numeric, dailyUnits: numeric, days: nullable(numeric) })),
  topProducts: list(fields({ id: text, name: text, units: numeric, amount: numeric })),
  sellers: list(fields({ id: nullable(text), name: text, orders: numeric, amount: numeric })),
  pendingOrders: list(order), recentOrders: list(order),
})

function check(value: unknown, validate: Validator) {
  if (!validate(value)) throw new Error('La respuesta del servidor no es compatible con esta pantalla. Actualice el backend y vuelva a intentar la consulta.')
}

export function parseDashboardData(value: unknown): DashboardData {
  check(value, summary)
  return value as DashboardData
}

export function parseDashboardReport(value: unknown): DashboardReport {
  check(value, report)
  return value as DashboardReport
}
