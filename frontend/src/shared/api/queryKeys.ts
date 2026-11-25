import type { QueryClient } from '@tanstack/react-query'

export const queryKeys = {
  orders: {
    all: ['orders'] as const,
    list: (request: string) => ['orders', 'list', request] as const,
    detail: (id: string | undefined) => ['orders', 'detail', id] as const,
    previousOrder: (customerId: string) => ['orders', 'previous-order', customerId] as const,
    reviews: ['orders', 'reviews'] as const,
    notificationContact: (orderId: string) => ['orders', 'notification-contact', orderId] as const,
    notificationStatus: (orderId: string, requestId: string | undefined) =>
      ['orders', 'notification-status', orderId, requestId] as const,
    reassignmentList: ['orders', 'reassignment-list'] as const,
  },
  sales: {
    all: ['sales'] as const,
    list: (request: string) => ['sales', 'list', request] as const,
    detail: (id: string) => ['sales', 'detail', id] as const,
    filterOptions: ['sales', 'filter-options'] as const,
    soldProducts: (query: string, search: string) => ['sales', 'sold-products', 'products', query, search] as const,
    soldProductHistory: (productId: string | undefined, query: string) =>
      ['sales', 'sold-products', 'history', productId, query] as const,
  },
  customers: {
    all: ['customers'] as const,
    list: (request: string) => ['customers', 'list', request] as const,
    detail: (id: string) => ['customers', 'detail', id] as const,
    orders: (id: string, request: string) => ['customers', 'orders', id, request] as const,
    associated: (id: string, request: string) => ['customers', 'associated', id, request] as const,
    filterOptions: ['customers', 'filter-options'] as const,
    assignmentContext: ['customers', 'assignment-context'] as const,
    resellers: (scope = 'all') => ['customers', 'resellers', scope] as const,
    resellerAssignment: (id: string) => ['customers', 'reseller-assignment', id] as const,
    accountBalance: (id: string) => ['customers', 'account-balance', id] as const,
    purchases: (request: string) => ['customers', 'purchases', request] as const,
    orderOptions: ['customers', 'order-options'] as const,
  },
  products: {
    all: ['products'] as const,
    list: (request: string) => ['products', 'list', request] as const,
    movements: (id: string, request: string) => ['products', 'movements', id, request] as const,
    allOptions: (scope: string) => ['products', 'all-options', scope] as const,
  },
  pricing: {
    all: ['pricing'] as const,
    lists: (request: string) => ['pricing', 'lists', request] as const,
    listOptions: (scope = 'all') => ['pricing', 'list-options', scope] as const,
    products: (request: string) => ['pricing', 'products', request] as const,
    history: (request: string) => ['pricing', 'history', request] as const,
    batch: (customerId: string, listId: string, productIds: string[], orderId: string | null) =>
      ['pricing', 'batch', customerId, listId, productIds, orderId] as const,
  },
  catalog: {
    all: ['catalog'] as const,
    brands: ['catalog', 'brands'] as const,
    categories: ['catalog', 'categories'] as const,
  },
  suppliers: {
    all: ['suppliers'] as const,
    list: (request: string) => ['suppliers', 'list', request] as const,
    options: (scope = 'all') => ['suppliers', 'options', scope] as const,
  },
  purchasing: {
    all: ['purchasing'] as const,
    list: (request: string) => ['purchasing', 'list', request] as const,
    detail: (id: string | undefined) => ['purchasing', 'detail', id] as const,
    last: (supplierId: string) => ['purchasing', 'last', supplierId] as const,
  },
  sellers: {
    all: ['sellers'] as const,
    list: (request: string) => ['sellers', 'list', request] as const,
    options: (scope = 'all') => ['sellers', 'options', scope] as const,
    reassignmentOptions: ['sellers', 'reassignment-options'] as const,
  },
  users: {
    all: ['users'] as const,
    list: (request: string) => ['users', 'list', request] as const,
    roles: (scope = 'all') => ['users', 'roles', scope] as const,
    permissions: ['users', 'roles', 'permissions'] as const,
    options: ['users', 'options'] as const,
  },
  zones: {
    all: ['zones'] as const,
  },
  dashboard: {
    all: ['dashboard'] as const,
    summary: (min: string | null, max: string | null) => ['dashboard', 'summary', min, max] as const,
    analytics: (start: string | null, end: string | null) => ['dashboard', 'analytics', start, end] as const,
    sellerOrders: (request: string) => ['dashboard', 'seller-orders', request] as const,
    orderReviews: ['dashboard', 'order-reviews'] as const,
  },
  expenses: {
    all: ['expenses'] as const,
    list: (request: string) => ['expenses', 'list', request] as const,
  },
  inventory: {
    all: ['inventory'] as const,
    shrinkages: (request: string) => ['inventory', 'shrinkages', request] as const,
  },
  commissions: {
    all: ['commissions'] as const,
    plans: ['commissions', 'plans'] as const,
    plan: (id: string) => ['commissions', 'plans', id] as const,
    assignments: (page: number, size: number) => ['commissions', 'assignments', page, size] as const,
    assignment: (path: string) => ['commissions', 'assignment', path] as const,
    options: (path: string) => ['commissions', 'options', path] as const,
    ruleOptions: (path: string) => ['commissions', 'rule-options', path] as const,
    statement: (path: string, query?: string) =>
      query !== undefined ? (['commissions', 'statement', path, query] as const) : (['commissions', 'statement', path] as const),
  },
  audit: {
    all: ['audit'] as const,
    list: (page: number, search: string) => ['audit', 'list', page, search] as const,
  },
  settings: {
    all: ['settings'] as const,
    businessProfile: ['settings', 'business-profile'] as const,
    businessLogo: ['settings', 'business-logo'] as const,
    creditLimit: ['settings', 'credit-limit'] as const,
  },
} as const

const domainRoots = {
  orders: ['orders', '/api/orders', 'order-reviews', 'order-create-previous-order'],
  sales: ['sales', '/api/sales', 'sale-detail', 'sold-products', 'customer-purchases'],
  customers: ['customers', '/api/customers', '/api/payments', 'customer-debts'],
  stock: ['products', 'inventory', '/api/inventory', '/api/products', 'order-create-products'],
  reports: ['dashboard', 'audit', '/api/dashboard', 'dashboard-analytics', '/api/audit', 'audit-events'],
  pricing: ['pricing', '/api/pricing', 'order-price-batch', 'order-create-price-lists'],
  sellers: ['sellers', '/api/sellers', 'seller-reassignment-options'],
  catalog: ['catalog', '/api/brands', '/api/categories'],
  suppliers: ['suppliers', '/api/suppliers'],
  purchasing: ['purchasing', 'supplier-orders', 'supplier-orders-last', '/api/supplier-orders'],
  users: ['users', '/api/users', '/api/roles'],
  zones: ['zones', '/api/zones'],
  expenses: ['expenses', '/api/expenses'],
  inventory: ['inventory', '/api/inventory'],
  commissions: ['commissions', 'commission-statement', 'commission-rule-options', '/api/settings/commission-plans'],
  settings: ['settings', '/api/settings'],
} as const

export type CacheDomain = keyof typeof domainRoots

function matchesExtraCrossDomain(domain: CacheDomain, queryKey: readonly unknown[]): boolean {
  const root = queryKey[0]
  const sub = queryKey[1]
  if (domain === 'orders') {
    return (root === 'customers' && sub === 'orders') || (root === 'dashboard' && sub === 'order-reviews')
  }
  if (domain === 'sales') {
    return (
      (root === 'customers' && (sub === 'purchases' || sub === 'orders')) ||
      (root === 'commissions' && sub === 'statement')
    )
  }
  if (domain === 'reports') {
    return (
      (root === 'sales' && (sub === 'sold-products' || sub === 'sold-product-history')) ||
      (root === 'customers' && sub === 'purchases')
    )
  }
  return false
}

export function invalidateDomains(client: QueryClient, ...domains: CacheDomain[]) {
  const roots: readonly string[] = domains.flatMap((domain) => [...domainRoots[domain]])
  return client.invalidateQueries({
    predicate: ({ queryKey }) => {
      const root = String(queryKey[0])
      if (roots.some((candidate) => root === candidate || root.startsWith(`${candidate}/`) || root.startsWith(`${candidate}?`))) {
        return true
      }
      return domains.some((domain) => matchesExtraCrossDomain(domain, queryKey))
    },
  })
}
