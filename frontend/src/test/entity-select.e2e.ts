import { expect, test } from '@playwright/test'

test('customer and seller pickers share search, keyboard and cancellation across forms', async ({ page }, testInfo) => {
  await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken',
    `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'SALE_PAYMENT', 'ORDER_CREATE'] }))}.signature`))
  const north = { id: 'customer-1', name: 'Almacén Norte', status: 'ACTIVE', balance: 200, sellerId: 'seller-1', seller: 'Lucía', priceListId: 'list-1' }
  const south = { ...north, id: 'customer-2', name: 'Mercado Sur' }
  const lucia = { id: 'seller-1', userId: 'user-1', displayName: 'Lucía', email: 'lucia@example.test', status: 'ACTIVE', assignedCustomersCount: 2 }
  const martin = { ...lucia, id: 'seller-2', displayName: 'Martín', email: 'martin@example.test' }
  const paged = (content: unknown[], currentPage = 0, totalPages = 1) => ({ content, page: currentPage, size: 20, totalElements: content.length * totalPages, totalPages })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const currentPage = Number(url.searchParams.get('page') || 0)
    let json: unknown = paged([])
    if (url.pathname === '/api/customers') json = paged(currentPage === 0 ? [south] : [north], currentPage, 2)
    else if (url.pathname === '/api/sellers') json = paged(currentPage === 0 ? [martin] : [lucia], currentPage, 2)
    else if (url.pathname === '/api/zones') json = []
    else if (url.pathname === '/api/customers/filter-options') json = { sellers: [{ id: lucia.id, name: lucia.displayName }, { id: martin.id, name: martin.displayName }] }
    else if (url.pathname === '/api/pricing/lists') json = paged([{ id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }])
    else if (url.pathname === '/api/products') json = paged([{ id: 'product-1', sku: 'HAR', name: 'Harina', status: 'ACTIVE' }])
    else if (url.pathname.endsWith('/last-order')) json = { available: false }
    await route.fulfill({ json })
  })

  async function choose(label: string, query: string, name: string | RegExp) {
    const input = page.getByRole('combobox', { name: label, exact: true })
    await expect(input).toBeEnabled()
    await input.fill(query)
    await expect(input).toHaveAttribute('aria-expanded', 'true')
    await expect(page.getByRole('option', { name, exact: typeof name === 'string' })).toBeVisible()
    await input.press('ArrowDown')
    await input.press('ArrowDown')
    await input.press('Enter')
    await expect(input).toHaveAttribute('aria-expanded', 'false')
    const confirmedName = await input.inputValue()
    await input.fill('no match')
    await expect(page.getByText('No se encontraron coincidencias.', { exact: true })).toBeVisible()
    await input.press('Escape')
    await expect(input).toHaveValue(confirmedName)
    const box = await input.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  }

  await page.goto('/orders/new')
  await choose('Cliente', 'ALMACEN', 'Almacén Norte')
  await choose('Vendedor', 'LUCIA', 'Lucía')
  await page.screenshot({ path: testInfo.outputPath('order-entity-selects.png'), fullPage: true })
  await page.goto('/payments')
  await page.getByRole('button', { name: '+ Registrar pago', exact: true }).click()
  await choose('Cliente del pago', 'ALMACEN', /Almacén Norte/)
  await page.screenshot({ path: testInfo.outputPath('payment-entity-select.png'), fullPage: true })
  await page.goto('/price-lists')
  await page.getByRole('tab', { name: 'Reglas de descuento' }).click()
  await page.getByRole('button', { name: '+ Nueva regla', exact: true }).first().click()
  await choose('Cliente (opcional)', 'ALMACEN', 'Almacén Norte')
  await page.goto('/admin/sellers')
  await page.getByRole('button', { name: 'Reasignar clientes', exact: true }).click()
  await choose('Vendedor de origen', 'LUCIA', 'Lucía')
  await choose('Vendedor de destino', 'MARTIN', 'Martín')
  await page.screenshot({ path: testInfo.outputPath('reassignment-entity-selects.png'), fullPage: true })
  await page.goto('/customers')
  await choose('Buscar por vendedor', 'LUCIA', 'Lucía')
  await page.getByRole('button', { name: '+ Nuevo cliente', exact: true }).click()
  await choose('Vendedor asignado', 'LUCIA', /Lucía/)
  await page.screenshot({ path: testInfo.outputPath('customer-form-entity-select.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
