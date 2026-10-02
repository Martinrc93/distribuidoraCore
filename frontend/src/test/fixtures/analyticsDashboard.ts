import type { DashboardReport } from '../../features/dashboard/AnalyticsDashboardPage'

export const analyticsReport: DashboardReport = {
  dateMin: '2026-09-01', dateMax: '2026-09-30', previousDateMin: '2026-08-02', previousDateMax: '2026-08-31', generatedAt: '2026-10-02T13:00:00Z',
  sales: { amount: 1500, orders: 3 }, previousSales: { amount: 1000, orders: 2 },
  collections: { amount: 900, cash: 500, transfer: 400 }, previousCollections: { amount: 600, cash: 600, transfer: 0 },
  current: { debt: 600, debtorCount: 1, pendingOrders: 2, pendingAmount: 800, oldestPendingAt: '2026-09-01T14:00:00Z', failedDeliveries: 1, stockAlertCount: 1 },
  trend: [{ date: '2026-09-01', amount: 500, orders: 1 }, { date: '2026-09-02', amount: 1000, orders: 2 }],
  topDebtors: [{ id: 'customer-1', name: 'Cliente principal', balance: 600 }],
  debtAging: { days0to30: 600, days31to60: 0, days61to90: 0, daysOver90: 0 },
  stockAlerts: [{ id: 'product-1', name: 'Producto faltante', stock: -2 }],
  stockCoverage: [{ id: 'product-2', name: 'Producto sin ventas', stock: 10, dailyUnits: 0, days: null }],
  topProducts: [{ id: 'product-3', name: 'Producto vendido', units: 5, amount: 1500 }],
  sellers: [{ id: 'seller-1', name: 'Vendedor principal', orders: 3, amount: 1500 }],
  pendingOrders: [{ id: 'order-1', number: 'ORD-001', customer: 'Cliente principal', seller: 'Vendedor principal', total: 800, status: 'CONFIRMED', date: '2026-09-01T14:00:00Z' }],
  recentOrders: [],
}
