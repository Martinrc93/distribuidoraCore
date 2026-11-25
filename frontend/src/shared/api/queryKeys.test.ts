import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { invalidateDomains, queryKeys } from './queryKeys'

describe('queryKeys and invalidateDomains', () => {
  it('invalidates hierarchical and cross-domain query keys cleanly', async () => {
    const client = new QueryClient()
    const keys = [
      queryKeys.orders.list('/api/orders?page=0'),
      queryKeys.orders.detail('order-1'),
      queryKeys.sales.list('/api/sales?page=0'),
      queryKeys.sales.filterOptions,
      queryKeys.sales.soldProducts('dateMin=2026-11-01', ''),
      queryKeys.customers.list('/api/customers?page=0'),
      queryKeys.customers.orders('cust-1', 'page=0'),
      queryKeys.customers.purchases('/api/reports/customers/cust-1/purchases'),
      queryKeys.products.list('/api/products?page=0'),
      queryKeys.pricing.lists('/api/pricing/lists?page=0'),
      queryKeys.catalog.brands,
      queryKeys.suppliers.list('/api/suppliers?page=0'),
      queryKeys.purchasing.list('/api/supplier-orders?page=0'),
      queryKeys.sellers.list('params'),
      queryKeys.users.list('/api/users?page=0'),
      queryKeys.zones.all,
      queryKeys.dashboard.analytics('2026-11-01', '2026-11-25'),
      queryKeys.expenses.list('/api/expenses?page=0'),
      queryKeys.inventory.shrinkages('/api/inventory/shrinkages?page=0'),
      queryKeys.commissions.statement('/api/commissions/sellers/s-1', 'page=0'),
      queryKeys.audit.list(0, ''),
      queryKeys.settings.businessProfile,
    ]
    for (const key of keys) client.setQueryData(key, { ok: true })

    await invalidateDomains(client, 'orders', 'sales', 'customers', 'stock', 'reports')

    for (const key of [
      queryKeys.orders.list('/api/orders?page=0'),
      queryKeys.orders.detail('order-1'),
      queryKeys.sales.list('/api/sales?page=0'),
      queryKeys.sales.filterOptions,
      queryKeys.sales.soldProducts('dateMin=2026-11-01', ''),
      queryKeys.customers.list('/api/customers?page=0'),
      queryKeys.customers.orders('cust-1', 'page=0'),
      queryKeys.customers.purchases('/api/reports/customers/cust-1/purchases'),
      queryKeys.products.list('/api/products?page=0'),
      queryKeys.dashboard.analytics('2026-11-01', '2026-11-25'),
      queryKeys.commissions.statement('/api/commissions/sellers/s-1', 'page=0'),
      queryKeys.audit.list(0, ''),
    ]) {
      expect(client.getQueryState(key)?.isInvalidated).toBe(true)
    }

    expect(client.getQueryState(queryKeys.zones.all)?.isInvalidated).toBe(false)
    expect(client.getQueryState(queryKeys.settings.businessProfile)?.isInvalidated).toBe(false)
  })
})
