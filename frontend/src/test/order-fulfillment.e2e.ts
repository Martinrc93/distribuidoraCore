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

test('document groups stack on mobile and the send options open their dialogs', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await mockWorkflow(page, true)
  await page.addInitScript(() => localStorage.setItem('distribuidora.text-size', 'large'))
  await page.route('**/api/orders/o1/notifications/contact', route => route.fulfill({ json: { email: 'compras@example.test', phone: null } }))
  await page.route('**/api/customers/customer-1/contact', route => {
    expect(route.request().postDataJSON()).toEqual({ phone: '+5491112345678' })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/orders/o1')
  const remito = page.getByRole('group', { name: 'Remito A4' })
  const ticket = page.getByRole('group', { name: 'Ticket 80 mm' })
  await expect(page.getByRole('group', { name: 'Enviar', exact: true })).toHaveCount(0)
  for (const [group, format] of [[remito, 'remito A4'], [ticket, 'ticket 80 mm']] as const) {
    const names = [`Enviar ${format} por WhatsApp`, `Imprimir ${format}`, `Enviar ${format} por email`, `Descargar ${format}`]
    const buttons = group.getByRole('button')
    await expect(buttons).toHaveCount(names.length)
    await buttons.first().focus()
    for (const [index, name] of names.entries()) {
      const button = buttons.nth(index)
      await expect(button).toHaveAccessibleName(name)
      await expect(button).toBeFocused()
      await expect(button).toHaveText('')
      await expect(button).toHaveAttribute('title', name)
      await expect(button).toHaveCSS('width', '44px')
      await expect(button).toHaveCSS('height', '44px')
      await expect(button.locator('svg')).toHaveAttribute('aria-hidden', 'true')
      if (index < names.length - 1) await page.keyboard.press('Tab')
    }
  }
  const groups = await Promise.all([remito, ticket].map(group => group.boundingBox()))
  expect(groups[1]!.y).toBeGreaterThan(groups[0]!.y)
  await noOverflow(page)

  const email = remito.getByRole('button', { name: 'Enviar remito A4 por email', exact: true })
  await email.click()
  let dialog = page.getByRole('dialog', { name: 'Enviar por email' })
  await expect(dialog.getByText('Remito A4', { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('Email del cliente')).toHaveValue('compras@example.test')
  await page.keyboard.press('Escape')
  await expect(email).toBeFocused()

  await ticket.getByRole('button', { name: 'Enviar ticket 80 mm por WhatsApp', exact: true }).click()
  dialog = page.getByRole('dialog', { name: 'Cargar teléfono' })
  await expect(dialog.getByText('Ticket 80 mm', { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('Teléfono de WhatsApp')).toBeFocused()
  const box = (await dialog.boundingBox())!
  expect(box.x).toBeGreaterThanOrEqual(0)
  expect(box.x + box.width).toBeLessThanOrEqual(320)
  await dialog.getByLabel('Teléfono de WhatsApp').fill('+54 9 11 1234-5678')
  await dialog.getByRole('button', { name: 'Guardar en ficha' }).click()
  await expect(page.getByRole('dialog', { name: 'Enviar por WhatsApp' }).getByRole('status')).toHaveText('Contacto guardado en la ficha del cliente.')
  await noOverflow(page)
})

test('order detail shares a surface across operations, products and totals', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  const state = await mockWorkflow(page, true)
  await page.addInitScript(() => localStorage.setItem('distribuidora.text-size', 'large'))
  await page.goto('/orders?status=CONFIRMED&dateMin=&dateMax=')
  const returnUrl = new URL(page.url())
  await page.getByRole('link', { name: 'Abrir pedido 000100001' }).click()
  await expect(page.getByRole('navigation', { name: 'Navegar entre pedidos' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Volver a pedidos' })).toHaveAttribute('href', returnUrl.pathname + returnUrl.search)

  const documentGroups = page.locator('.order-document-group')
  await expect(documentGroups).toHaveCount(2)
  for (const button of await documentGroups.getByRole('button').all()) {
    await expect(button).toHaveCSS('width', '32px')
    await expect(button).toHaveCSS('height', '32px')
    const box = (await button.boundingBox())!
    const icon = (await button.locator('svg').boundingBox())!
    expect(icon.x - box.x).toBeCloseTo(box.x + box.width - icon.x - icon.width)
    expect(icon.y - box.y).toBeCloseTo(box.y + box.height - icon.y - icon.height)
  }
  const documentBoxes = await Promise.all((await documentGroups.all()).map(group => group.boundingBox()))
  for (const box of documentBoxes.slice(1)) {
    expect(box!.y).toBeCloseTo(documentBoxes[0]!.y)
    expect(box!.width).toBeCloseTo(documentBoxes[0]!.width, 0)
  }
  for (const group of await documentGroups.all()) {
    const box = (await group.boundingBox())!
    const first = (await group.getByRole('button').first().boundingBox())!
    const last = (await group.getByRole('button').last().boundingBox())!
    expect(box.x + box.width - last.x - last.width).toBeCloseTo(first.x - box.x)
  }
  const panel = page.locator('section.panel').filter({ has: page.getByRole('heading', { name: 'Operaciones del pedido' }) })
  const products = panel.locator('section').filter({ has: page.getByRole('heading', { name: 'Productos del pedido' }) })
  const payments = panel.locator('section').filter({ has: page.getByRole('heading', { name: 'Pagos registrados' }) })
  const totals = panel.locator('section').filter({ has: page.getByRole('heading', { name: 'Detalle del pedido' }) })
  for (const section of [products, payments, totals]) await expect(section).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
  expect(await products.evaluate(element => parseFloat(getComputedStyle(element).borderTopWidth))).toBeCloseTo(1, 0)
  expect(await totals.evaluate(element => parseFloat(getComputedStyle(element).borderLeftWidth))).toBeCloseTo(1, 0)
  const operations = panel.locator('.order-detail-operations')
  const documentsPanel = panel.locator('.order-documents')
  await expect(documentsPanel.getByRole('heading', { name: 'Documentos del pedido' })).toHaveCount(0)
  await expect(documentsPanel.getByRole('heading', { name: 'Remito A4', level: 3 })).toBeVisible()
  const documentsBox = (await documentsPanel.boundingBox())!
  const firstGroup = documentBoxes[0]!
  const lastGroup = documentBoxes.at(-1)!
  expect(documentsBox.x + documentsBox.width - lastGroup.x - lastGroup.width).toBeCloseTo(firstGroup.x - documentsBox.x)
  expect(firstGroup.y).toBeCloseTo(documentsBox.y)

  const operationsBox = (await operations.boundingBox())!
  const panelBox = (await panel.boundingBox())!
  expect(documentsBox.x).toBeGreaterThan(operationsBox.x + operationsBox.width)
  expect(documentsBox.y).toBeCloseTo(operationsBox.y)
  expect(panelBox.x + panelBox.width - documentsBox.x - documentsBox.width).toBeCloseTo(operationsBox.x - panelBox.x)
  const header = operations.locator('.panel-header')
  const heading = (await header.getByRole('heading', { name: 'Operaciones del pedido' }).boundingBox())!
  const status = (await header.getByText('A preparar', { exact: true }).boundingBox())!
  expect(status.x).toBeGreaterThan(heading.x + heading.width)
  expect(status.y + status.height / 2).toBeCloseTo(heading.y + heading.height / 2)
  await expect(header.getByText('Vendedor · Pendiente de revisión', { exact: true })).toBeVisible()

  const controls = [
    panel.getByRole('button', { name: 'Preparar pedido 000100001' }),
    panel.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' }),
    panel.getByRole('button', { name: 'Registrar entrega' }),
    panel.getByRole('button', { name: 'Cancelar pedido' }),
  ]
  const boxes = await Promise.all(controls.map(async (control) => {
    await expect(control).toBeVisible()
    return (await control.boundingBox())!
  }))
  const row = (await panel.locator('#order-operations').boundingBox())!
  const cancel = boxes.at(-1)!
  expect(row.y).toBeGreaterThan(heading.y + heading.height)
  expect(Math.abs(cancel.x + cancel.width - row.x - row.width)).toBeLessThan(1)
  await expect(controls.at(-1)!).toHaveClass(/\bbutton-danger\b/)
  const note = (await panel.getByText(/^Pedido histórico/).boundingBox())!
  expect(note.y).toBeGreaterThan(Math.max(...boxes.map(({ y, height }) => y + height)))

  await page.setViewportSize({ width: 320, height: 900 })
  state.prepared = true
  await page.reload()
  await expect(header.getByText('Preparado', { exact: true })).toBeVisible()
  expect(await totals.evaluate(element => parseFloat(getComputedStyle(element).borderTopWidth))).toBeCloseTo(1, 0)
  await expect(totals).toHaveCSS('border-left-width', '0px')
  const mobileOperations = (await operations.boundingBox())!
  expect((await documentsPanel.boundingBox())!.y).toBeGreaterThan(mobileOperations.y + mobileOperations.height)
  await noOverflow(page)
})

for (const width of [320, 390, 1280]) {
  test(`admin prepares and reviews seller orders at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await mockWorkflow(page, true)
    await page.goto('/orders?dateMin=&dateMax=')
    const badge = page.getByText('Vendedor · Pendiente de revisión', { exact: true })
    const rowMenu = page.getByRole('button', { name: 'Más acciones del pedido 000100001' })
    await rowMenu.click()
    await expect(page.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' })).toBeVisible()
    await page.keyboard.press('Escape')
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
    await expect(page.getByRole('button', { name: 'Pasar a entregado pedido 000100001' })).toBeVisible()
    await rowMenu.click()
    await expect(page.getByRole('button', { name: 'Confirmar revisión del pedido 000100001' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(badge).toHaveCount(0)
    expect(state.prepared).toBe(true)
    await page.getByRole('group', { name: 'Estado del pedido' }).getByRole('button', { name: /^Por entregar/ }).click()
    await page.getByRole('group', { name: 'Preparación' }).getByRole('button', { name: /^Listos para entregar/ }).click()
    await expect(page.getByRole('group', { name: 'Preparación' }).getByRole('button', { name: /^Listos para entregar/ })).toHaveAttribute('aria-pressed', 'true')
    await rowMenu.click()
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
