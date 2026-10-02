import { expect, test } from '@playwright/test'

const customer = { id: 'customer-1', name: 'Almacén Norte', cuitId: '30-12345678-9', email: 'contacto@almacennorte.example.test', phone: '11 1234-5678', address: 'Av. San Martín 100, local 4', zone: 'Centro', seller: 'Lucía', priceList: 'General', priceListCode: 'GENERAL', balance: 4500.50, status: 'ACTIVE', createdAt: '2025-03-01T12:00:00Z' }
const order = { id: 'order-1', number: 'PED-001', seller: 'Lucía', total: 1250.50, status: 'CANCELLED', date: '2025-04-01T12:00:00Z' }
const paged = (content: unknown[], page = 0, totalElements = content.length) => ({ content, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) })

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken',
    `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`))
})

test('opens customer data, pages exact history, opens an order and returns to list filters', async ({ page }, testInfo) => {
  const historyPaths: string[] = []
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const url = new URL(route.request().url())
    let json: unknown = paged([])
    if (url.pathname === '/api/customers') json = paged([customer])
    else if (url.pathname === '/api/customers/filter-options') json = { sellers: [] }
    else if (url.pathname === '/api/zones') json = []
    else if (url.pathname === '/api/customers/customer-1') json = customer
    else if (url.pathname === '/api/customers/customer-1/orders') {
      historyPaths.push(url.pathname + url.search)
      const currentPage = Number(url.searchParams.get('page'))
      json = paged(currentPage === 0 ? Array.from({ length: 20 }, (_, index) => ({ ...order, id: `order-${index + 1}`, number: `PED-${String(index + 1).padStart(3, '0')}`, status: index === 0 ? 'CANCELLED' : 'DELIVERED' })) : [{ ...order, number: 'PED-OLD' }], currentPage, 21)
    } else if (url.pathname === '/api/orders/order-1') {
      json = { order: { ...order, customer: customer.name, customerId: customer.id, subtotal: 1250.50, discount: 0, customerBalance: 4500.50 }, items: [], sale: { id: 'sale-1', number: 'V-001', total: 1250.50, paid: 0, balance: 1250.50, status: 'CANCELLED', date: order.date }, payments: [], deliveryAttempts: [], account: { debit: 1250.50, credit: 0, net: 1250.50 } }
    }
    await route.fulfill({ json })
  })
  await page.goto('/customers?search=Norte&status=ACTIVE')
  await page.getByRole('link', { name: 'Ver cliente Almacén Norte' }).click()
  await expect(page.getByRole('heading', { name: customer.name, exact: true })).toBeVisible()
  await expect(page.getByText(customer.email, { exact: true })).toBeVisible()
  await expect(page.getByText('GENERAL - General', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Ver pedido PED-001', exact: true })).toBeVisible()
  await expect(page.getByText('Cancelado', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('customer-data.png') })
  await page.getByRole('heading', { name: 'Historial de pedidos' }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('customer-history.png') })
  await page.getByRole('button', { name: 'Siguiente', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Ver pedido PED-OLD' })).toBeVisible()
  await expect(page).toHaveURL(/ordersPage=1/)
  expect(historyPaths).toContain('/api/customers/customer-1/orders?page=1&size=20')
  await page.getByRole('link', { name: 'Ver pedido PED-OLD' }).click()
  await expect(page).toHaveURL(/\/orders\/order-1$/)
  await expect(page.getByRole('heading', { name: /PED-001/ })).toBeVisible()
  await page.goBack()
  await page.getByRole('link', { name: 'Volver a clientes' }).click()
  await expect(page.getByRole('textbox', { name: 'Buscar clientes' })).toHaveValue('Norte')
  await page.getByRole('link', { name: 'Ver cliente Almacén Norte' }).click()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('keeps data visible when history fails and allows retry', async ({ page }) => {
  let fail = true
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/orders')) return route.fulfill({ status: fail ? 500 : 200, json: fail ? { detail: 'Error' } : paged([]) })
    return route.fulfill({ json: { ...customer, address: 'Una dirección extensa '.repeat(8), email: 'un-nombre-de-contacto-muy-largo-sin-espacios@example.test' } })
  })
  await page.goto('/customers/customer-1')
  await expect(page.getByRole('heading', { name: 'No se pudo cargar el historial' })).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('heading', { name: customer.name, exact: true })).toBeVisible()
  fail = false
  await page.getByRole('button', { name: 'Reintentar historial' }).click()
  await expect(page.getByRole('heading', { name: 'No hay pedidos para mostrar' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('a missing or inaccessible customer does not request history', async ({ page }) => {
  let historyRequested = false
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    if (route.request().url().includes('/orders?')) historyRequested = true
    await route.fulfill({ status: 404, json: { detail: 'Not found' } })
  })
  await page.goto('/customers/customer-1')
  await expect(page.getByRole('heading', { name: 'Cliente no disponible' })).toBeVisible({ timeout: 15000 })
  expect(historyRequested).toBe(false)
  await expect(page.getByRole('link', { name: 'Volver a clientes' })).toHaveAttribute('href', '/customers')
})
