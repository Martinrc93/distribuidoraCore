import { expect, test, type Page } from '@playwright/test'

const authorities = (admin: boolean) => admin ? ['ADMIN_ALL'] : ['ORDER_CREATE', 'ORDER_PREPARE', 'SALE_DELIVER']
async function mockWorkflow(page: Page, admin: boolean) {
  await page.addInitScript(values => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: values }))}.signature`), authorities(admin))
  const state = { prepared: false, reviewed: false, delivered: false }
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path === '/api/orders/o1/prepare') { state.prepared = true; return route.fulfill({ status: 204 }) }
    if (path === '/api/orders/o1/review') { state.reviewed = true; return route.fulfill({ status: 204 }) }
    if (path === '/api/orders/o1/delivery-attempts') { state.delivered = true; state.prepared = true; return route.fulfill({ status: 204 }) }
    if (path === '/api/orders/o1') return route.fulfill({ json: { order: { status: 'CONFIRMED' }, sale: { balance: 10, previousDebtAvailable: 0 } } })
    if (path === '/api/dashboard') {
      const metrics = { performedOrders: 1, deliveredOrders: 0, totalBilled: 10, totalPaid: 0, cashPaid: 0, transferPaid: 0, accountBalance: 10 }
      return route.fulfill({ json: { dateMin: '2026-11-16', dateMax: '2026-11-16', totals: metrics, bySeller: [] } })
    }
    const order = { id: 'o1', number: '000100001', customer: 'Almacén con un nombre largo para comprobar la adaptación', seller: 'Lucía', total: 10, paid: 0, date: '2026-11-16T12:00:00Z', createdByAdmin: false, status: state.delivered ? 'DELIVERED' : 'CONFIRMED', fulfillmentStatus: state.prepared ? 'PREPARED' : 'PENDING', reviewPending: !state.reviewed }
    const content = url.searchParams.get('reviewPending') === 'true' && state.reviewed ? [] : [order]
    await route.fulfill({ json: { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 } })
  })
  return state
}

async function noOverflow(page: Page) {
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
}

for (const width of [320, 390, 1280]) {
  test(`admin prepares and reviews seller orders at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await mockWorkflow(page, true)
    await page.goto('/orders?dateMin=&dateMax=')
    const badge = page.getByText('Vendedor · Pendiente de revisión', { exact: true })
    await expect(badge).toBeVisible()
    await noOverflow(page)
    await page.screenshot({ path: info.outputPath(`seller-pending-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: 'Preparar pedido 000100001' }).click()
    let dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('button', { name: 'Volver' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Preparar pedido 000100001' })).toBeFocused()
    await page.getByRole('button', { name: 'Preparar pedido 000100001' }).click()
    dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: 'Confirmar preparación' }).click()
    await expect(page.getByText('Preparado', { exact: true })).toBeVisible()
    await expect(badge).toBeVisible()
    expect(state.prepared).toBe(true)
    await page.getByLabel('Filtrar por preparación').selectOption('PREPARED')
    await page.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' }).click()
    dialog = page.getByRole('dialog')
    await page.screenshot({ path: info.outputPath(`review-dialog-${width}.png`), fullPage: true })
    await dialog.getByRole('button', { name: 'Confirmar revisión', exact: true }).click()
    await expect(badge).toHaveCount(0)
    expect(state.reviewed).toBe(true)
    await noOverflow(page)

    // Delivered orders awaiting the end-of-day review remain visible across dates.
    state.reviewed = false
    state.delivered = true
    await page.goto('/dashboard')
    await expect(page.getByRole('heading', { name: 'Pedidos de vendedores por revisar (1)' })).toBeVisible()
    await expect(badge).toBeVisible()
    await page.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Confirmar revisión', exact: true }).click()
    await expect(page.getByText('No hay pedidos pendientes de revisión.')).toBeVisible()
    await expect(page.getByRole('link', { name: 'Ver todos los pendientes' })).toBeFocused()
    await noOverflow(page)
  })
}

test('seller prepares and delivers while administrative review is pending', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await mockWorkflow(page, false)
  await page.goto('/orders?dateMin=&dateMax=')
  await expect(page.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Preparar pedido 000100001' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmar preparación' }).click()
  await expect(page.getByText('Preparado', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Pasar a entregado pedido 000100001' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmar entrega' }).click()
  await expect(page.getByRole('button', { name: 'Pasar a entregado pedido 000100001' })).toHaveCount(0)
  expect(state.delivered).toBe(true)
  expect(state.reviewed).toBe(false)
  await noOverflow(page)
})
