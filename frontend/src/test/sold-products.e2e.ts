import { expect, test, type Page } from '@playwright/test'

const reportPath = '/api/reports/sold-products'
const totals = { productId: 'product-1', product: 'Harina integral', salesCount: 21, soldUnits: 14, returnedUnits: 1.5, netUnits: 12.5, amount: 420 }

async function setup(page: Page, authorities: string[], dark = false, productName = 'Harina integral') {
  await page.addInitScript(({ authorities, dark }) => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
    if (dark) localStorage.setItem('distribuidora.theme', 'dark')
  }, { authorities, dark })
  const requested: URL[] = []
  const productTotals = { ...totals, product: productName }
  await page.route((url) => url.pathname.startsWith('/api/'), (route) => {
    const url = new URL(route.request().url())
    requested.push(url)
    const pageIndex = Number(url.searchParams.get('page') ?? 0)
    if (url.pathname === '/api/sales/filter-options') return route.fulfill({ json: { customers: [], sellers: [] } })
    if (url.pathname === reportPath) {
      const product = pageIndex === 0 ? { ...productTotals, productId: 'product-2', product: 'Harina común' } : productTotals
      return route.fulfill({ json: { content: [product], totalElements: 21, totalPages: 2, page: pageIndex, size: 20 } })
    }
    if (url.pathname === `${reportPath}/product-1/sales`) {
      const sale = {
        ...totals, id: `sale-${pageIndex + 1}`, orderId: `order-${pageIndex + 1}`, number: `VENTA-00${pageIndex + 1}`,
        customer: 'Comercio de prueba', date: '2026-10-10T03:00:00Z', status: 'DELIVERED',
        soldUnits: 4, returnedUnits: 1.5, netUnits: 2.5, amount: 80.75,
      }
      return route.fulfill({ json: { product: productTotals, sales: { content: [sale], totalElements: 21, totalPages: 2, page: pageIndex, size: 20 } } })
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 } })
  })
  return requested
}

for (const width of [320, 390, 1440]) {
  test(`filters sales by product and preserves summary and detail pagination at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const productName = width === 320 ? `Harina ${'Integral'.repeat(12)}` : 'Harina integral'
    const requested = await setup(page, [width === 1440 ? 'ADMIN_ALL' : 'REPORTS_VIEW'], width === 390, productName)
    await page.goto('/sales')
    await page.getByRole('link', { name: 'Artículos vendidos' }).click()
    await expect(page.getByRole('heading', { name: 'Artículos vendidos', exact: true })).toBeVisible()
    await page.getByLabel('Desde', { exact: true }).fill('10/10/2026')
    await page.getByLabel('Hasta', { exact: true }).fill('09/10/2026')
    await expect(page.getByRole('alert')).toContainText('La fecha inicial no puede ser posterior a la final.')
    await expect(page.getByText('Harina común', { exact: true })).toHaveCount(0)
    await page.getByLabel('Hasta', { exact: true }).fill('10/10/2026')
    await page.getByRole('textbox', { name: 'Buscar artículo' }).fill('Harina')
    await expect(page.getByRole('link', { name: 'Ver ventas de Harina común' })).toBeVisible()
    expect(requested.some((url) => url.pathname === reportPath && url.searchParams.get('search') === 'Harina'
      && url.searchParams.get('dateMin') === '2026-10-10' && url.searchParams.get('dateMax') === '2026-10-10')).toBe(true)
    await page.getByRole('button', { name: 'Siguiente' }).click()
    const drill = page.getByRole('link', { name: `Ver ventas de ${productName}` })
    await expect(drill).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`articulos-${width}.png`), fullPage: true })
    await drill.click()
    await expect(page.getByRole('heading', { name: productName, exact: true })).toBeVisible()
    await expect(page.getByText('12,5', { exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Ver pedido de la venta VENTA-001' })).toHaveAttribute('href', '/orders/order-1')
    await page.getByRole('button', { name: 'Siguiente' }).click()
    await expect(page.getByText('VENTA-002', { exact: true })).toBeVisible()
    expect(new URL(page.url()).searchParams.get('historyPage')).toBe('1')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`ventas-articulo-${width}.png`), fullPage: true })
    await page.getByRole('link', { name: 'Volver a artículos vendidos' }).click()
    await expect(page.getByRole('link', { name: `Ver ventas de ${productName}` })).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'Buscar artículo' })).toHaveValue('Harina')
    await expect(page.getByLabel('Desde', { exact: true })).toHaveValue('10/10/2026')
    expect(new URL(page.url()).searchParams.has('historyPage')).toBe(false)
  })
}

test('restricts deep links and the report action to authorized roles', async ({ page }) => {
  const requested = await setup(page, ['ORDER_CREATE'])
  await page.goto('/sales/products/product-1')
  await expect(page).toHaveURL(/\/sales$/)
  await expect(page.getByRole('link', { name: 'Artículos vendidos' })).toHaveCount(0)
  expect(requested.some((url) => url.pathname.startsWith(reportPath))).toBe(false)
})
