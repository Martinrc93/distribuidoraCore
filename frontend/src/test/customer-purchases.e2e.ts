import { expect, test, type Page } from '@playwright/test'

const customer = { id: 'c1', name: 'Almacén de prueba', seller: 'Vendedor asignado', status: 'ACTIVE', balance: 0 }
const reportPath = '/api/reports/customers/c1/purchases'
const documentPath = '/api/documents/customers/c1/purchases'

async function setup(page: Page, authorities: string[], dark = false, product = 'Harina integral') {
  await page.addInitScript(({ authorities, dark }) => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
    if (dark) localStorage.setItem('distribuidora.theme', 'dark')
  }, { authorities, dark })
  const requested: URL[] = []
  await page.route((url) => url.pathname.startsWith('/api/'), (route) => {
    const url = new URL(route.request().url())
    requested.push(url)
    if (url.pathname === '/api/customers/c1') return route.fulfill({ json: customer })
    if (url.pathname === documentPath) return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.7\n%%EOF' })
    if (url.pathname === reportPath) {
      const index = Number(url.searchParams.get('page'))
      const number = index + 1
      const sale = {
        id: `sale-${number}`, orderId: `order-${number}`, saleNumber: `V-00${number}`, orderNumber: `P-00${number}`,
        date: '2026-10-10T03:00:00Z', status: 'DELIVERED', amount: 120.75,
        items: [{ id: `line-${number}`, product, soldUnits: 6, returnedUnits: 1.5, netUnits: 4.5, amount: 120.75 }],
      }
      return route.fulfill({ json: {
        summary: { customerId: customer.id, customer: customer.name, dateMin: url.searchParams.get('dateMin'),
          dateMax: url.searchParams.get('dateMax'), salesCount: 21, totalAmount: 1200.75 },
        purchases: { content: [sale], totalElements: 21, totalPages: 2, page: index, size: 20 },
      } })
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 } })
  })
  return requested
}

for (const width of [320, 390, 1440]) {
  test(`abre, filtra y descarga las compras del cliente a ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const product = width === 320 ? `Harina ${'Integral'.repeat(12)}` : 'Harina integral'
    const requested = await setup(page, width === 1440 ? ['ADMIN_ALL'] : ['REPORTS_VIEW', 'CUSTOMER_VIEW'], width === 390, product)
    const filters = new URLSearchParams({ ordersPage: '1', returnTo: '/customers?search=almacen' })
    await page.goto(`/customers/c1?${filters}`)
    await page.getByRole('link', { name: 'Ver compras del cliente' }).click()
    await expect(page.getByRole('heading', { name: 'Compras del cliente', exact: true })).toBeVisible()
    await page.getByLabel('Desde', { exact: true }).fill('10/10/2026')
    const until = page.getByLabel('Hasta', { exact: true })
    await until.fill('09/10/2026')
    await expect(page.getByRole('alert')).toContainText('La fecha inicial no puede ser posterior a la final.')
    await expect(page.getByRole('button', { name: 'Descargar PDF' })).toHaveCount(0)
    await until.fill('10/10/2026')
    await expect(page.getByText('21 ventas en el período.', { exact: false })).toBeVisible()
    const first = page.locator('summary').filter({ hasText: 'Pedido P-001' })
    await first.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText(product, { exact: true })).toBeVisible()
    await expect(page.getByText('4,5', { exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Ver pedido P-001' })).toHaveAttribute('href', '/orders/order-1')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`compras-cliente-${width}.png`), fullPage: true })

    await page.getByRole('button', { name: 'Siguiente' }).click()
    await expect(page.locator('summary').filter({ hasText: 'Pedido P-002' })).toBeVisible()
    await expect(page.getByText(/1\.200,75/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Imprimir compras' })).toBeEnabled()
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: 'Descargar PDF' }).click(),
    ])
    expect(download.suggestedFilename()).toBe('compras-2026-10-10-2026-10-10.pdf')
    expect(Object.fromEntries(requested.find((url) => url.pathname === documentPath)!.searchParams)).toEqual({
      dateMin: '2026-10-10', dateMax: '2026-10-10',
    })
    expect(requested.some((url) => url.pathname === reportPath && url.searchParams.get('dateMax') === '2026-10-09')).toBe(false)
    await page.getByRole('link', { name: 'Volver a la ficha' }).click()
    await expect(page.getByRole('heading', { name: customer.name, exact: true })).toBeVisible()
    expect(Object.fromEntries(new URL(page.url()).searchParams)).toEqual(Object.fromEntries(filters))
  })
}

test('la ficha y el enlace directo respetan el permiso de reportes', async ({ page }) => {
  const requested = await setup(page, ['CUSTOMER_VIEW'])
  await page.goto('/customers/c1')
  await expect(page.getByRole('heading', { name: customer.name, exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Ver compras del cliente' })).toHaveCount(0)
  await page.goto('/customers/c1/purchases')
  await expect(page).toHaveURL(/\/customers$/)
  expect(requested.some((url) => url.pathname === reportPath)).toBe(false)
})

test('el permiso de reportes permite consultar compras por enlace directo', async ({ page }) => {
  const requested = await setup(page, ['REPORTS_VIEW'])
  await page.goto('/customers/c1/purchases')
  await expect(page.getByText(customer.name, { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Volver a clientes' })).toHaveAttribute('href', '/customers')
  expect(requested.some((url) => url.pathname === '/api/customers/c1')).toBe(false)
})
