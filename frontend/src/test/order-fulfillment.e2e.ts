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
    if (path === '/api/dashboard') {
      const metrics = { performedOrders: 1, deliveredOrders: 0, totalBilled: 10, totalPaid: 0, cashPaid: 0, transferPaid: 0, accountBalance: 10 }
      return route.fulfill({ json: { dateMin: '2026-11-16', dateMax: '2026-11-16', totals: metrics, bySeller: [] } })
    }
    const order = { id: 'o1', number: '000100001', customer: 'Almacén con un nombre largo para comprobar la adaptación', seller: 'Lucía', total: 10, paid: 0, date: '2026-11-16T12:00:00Z', createdByAdmin: false, status: state.delivered ? 'DELIVERED' : 'CONFIRMED', fulfillmentStatus: state.prepared ? 'PREPARED' : 'PENDING', reviewPending: !state.reviewed }
    if (path === '/api/orders/o1') return route.fulfill({ json: {
      order: { ...order, customerId: 'customer-1', subtotal: 10, discount: 0, customerBalance: 10, legacyFulfillment: true },
      sale: { number: order.number, status: order.status, total: 10, paid: 0, balance: 10, previousDebtAvailable: 0 },
      items: [], payments: [], account: { debit: 10, credit: 0, net: 10 },
    } })
    const content = url.searchParams.get('reviewPending') === 'true' && state.reviewed ? [] : [order]
    await route.fulfill({ json: { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 } })
  })
  return state
}

async function noOverflow(page: Page) {
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
}

test('separate document channels preload contacts and open a mobile modal for missing details', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await mockWorkflow(page, true)
  await page.addInitScript(() => localStorage.setItem('distribuidora.text-size', 'large'))
  await page.route('**/api/orders/o1/notifications/contact', route => route.fulfill({ json: { email: 'compras@example.test', phone: null } }))
  await page.route('**/api/customers/customer-1/contact', route => {
    expect(route.request().postDataJSON()).toEqual({ phone: '+5491112345678' })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/orders/o1')
  const email = page.getByRole('button', { name: 'Enviar por email', exact: true })
  await email.click()
  let dialog = page.getByRole('dialog', { name: 'Enviar por email' })
  await expect(dialog.getByLabel('Email del cliente')).toHaveValue('compras@example.test')
  await page.keyboard.press('Escape')
  await expect(email).toBeFocused()

  await page.getByRole('button', { name: 'Enviar por WhatsApp', exact: true }).click()
  dialog = page.getByRole('dialog', { name: 'Cargar teléfono' })
  await expect(dialog.getByLabel('Teléfono de WhatsApp')).toBeFocused()
  const box = (await dialog.boundingBox())!
  expect(box.x).toBeGreaterThanOrEqual(0)
  expect(box.x + box.width).toBeLessThanOrEqual(320)
  await dialog.getByLabel('Teléfono de WhatsApp').fill('+54 9 11 1234-5678')
  await dialog.getByRole('button', { name: 'Guardar en ficha' }).click()
  await expect(page.getByRole('dialog', { name: 'Enviar por WhatsApp' }).getByRole('status')).toHaveText('Contacto guardado en la ficha del cliente.')
  await noOverflow(page)
})

test('order detail aligns buttons right and highlights cancellation in the shared row', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await mockWorkflow(page, true)
  await page.addInitScript(() => localStorage.setItem('distribuidora.text-size', 'large'))
  await page.goto('/orders/o1')

  const panel = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Operaciones del pedido' }) })
  const controls = [
    panel.getByText('A preparar', { exact: true }),
    panel.getByText('Vendedor · Pendiente de revisión', { exact: true }),
    panel.getByRole('button', { name: 'Preparar pedido 000100001' }),
    panel.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' }),
    panel.getByRole('button', { name: 'Registrar entrega' }),
    panel.getByRole('button', { name: 'Cancelar pedido' }),
  ]
  const boxes = await Promise.all(controls.map(async (control) => {
    await expect(control).toBeVisible()
    return (await control.boundingBox())!
  }))
  const midpoints = boxes.map(({ y, height }) => y + height / 2)
  expect(Math.max(...midpoints) - Math.min(...midpoints)).toBeLessThan(1)
  const row = (await panel.locator('#order-operations').boundingBox())!
  const [status] = boxes
  const cancel = boxes.at(-1)!
  expect(Math.abs(status.x - row.x)).toBeLessThan(1)
  expect(Math.abs(cancel.x + cancel.width - row.x - row.width)).toBeLessThan(1)
  await expect(controls[5]).toHaveClass(/\bbutton-danger\b/)
  const note = (await panel.getByText(/^Pedido histórico/).boundingBox())!
  expect(note.y).toBeGreaterThan(Math.max(...boxes.map(({ y, height }) => y + height)))

  await page.setViewportSize({ width: 320, height: 900 })
  expect((await controls[5].boundingBox())!.y).toBeGreaterThan((await controls[0].boundingBox())!.y)
  await noOverflow(page)
})

for (const width of [320, 390, 1280]) {
  test(`admin prepares and reviews seller orders at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await mockWorkflow(page, true)
    await page.goto('/orders?dateMin=&dateMax=')
    const badge = page.getByText('Vendedor · Pendiente de revisión', { exact: true })
    await expect(page.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' })).toBeVisible()
    await expect(badge).toHaveCount(0)
    await expect(page.getByText('Cargado por:', { exact: false })).toHaveCount(0)
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
    await expect(page.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' })).toBeVisible()
    await expect(badge).toHaveCount(0)
    expect(state.prepared).toBe(true)
    const moreFilters = page.getByRole('button', { name: /^Más filtros/ })
    if (await moreFilters.isVisible()) await moreFilters.click()
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
