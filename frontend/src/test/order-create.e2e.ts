import { expect, test } from '@playwright/test'

test('previous orders are disabled without history and admins choose whether to copy discounts', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'ORDER_CREATE'] }))}.signature`)
  })
  const paged = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    let json: unknown = {}
    if (path === '/api/customers') json = paged([
      { id: 'c1', name: 'Cliente con historial', priceListId: 'list1', sellerId: 'seller1' },
      { id: 'c2', name: 'Cliente sin historial', priceListId: 'list1', sellerId: 'seller1' },
    ])
    if (path === '/api/products') json = paged([{ id: 'p1', name: 'Harina', sku: 'HAR-1', presentation: 'Bolsa', status: 'ACTIVE' }])
    if (path === '/api/pricing/lists') json = paged([{ id: 'list1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }])
    if (path === '/api/sellers') json = paged([{ id: 'seller1', displayName: 'Lucía' }])
    if (path === '/api/pricing/resolve-batch') json = [{ productId: 'p1', priceListId: 'list1', priceListCode: 'GENERAL', unitPrice: 100 }]
    if (path === '/api/customers/c2/last-order') json = { available: false }
    if (path === '/api/customers/c1/last-order') json = { available: true, orderId: 'old', orderNumber: 'PED-ANTERIOR', orderDiscountPercent: 5, items: [{ productId: 'p1', productName: 'Harina', sku: 'HAR-1', presentation: 'Bolsa', status: 'ACTIVE', stock: 12, quantity: 2, lineDiscountPercent: 10 }] }
    await route.fulfill({ json })
  })
  await page.goto('/orders/new')
  const load = page.getByRole('button', { name: 'Cargar pedido anterior' })
  await expect(load).toBeDisabled()
  await page.getByRole('combobox', { name: 'Cliente', exact: true }).fill('Cliente sin historial')
  await page.getByRole('option', { name: 'Cliente sin historial', exact: true }).click()
  await expect(load).toHaveAttribute('title', 'Este cliente no tiene pedidos anteriores.')
  await expect(load).toBeDisabled()
  expect(await load.evaluate((button) => getComputedStyle(button).backgroundColor)).toBe('rgb(232, 239, 235)')
  await page.screenshot({ path: testInfo.outputPath('previous-order-disabled.png'), fullPage: true })
  await page.getByRole('combobox', { name: 'Cliente', exact: true }).fill('Cliente con historial')
  await page.getByRole('option', { name: 'Cliente con historial', exact: true }).click()
  await expect(load).toBeEnabled()
  if (testInfo.project.name.startsWith('desktop')) {
    const boxes = await Promise.all([page.getByRole('combobox', { name: 'Cliente', exact: true }), page.getByLabel('Vendedor', { exact: true }), page.getByLabel('Lista de precios', { exact: true }), load].map((element) => element.boundingBox()))
    const bottom = boxes[0]!.y + boxes[0]!.height
    for (const box of boxes) expect(Math.abs(box!.y + box!.height - bottom)).toBeLessThanOrEqual(1)
    expect(boxes[3]!.x).toBeGreaterThan(boxes[2]!.x)
  }
  await load.click()
  const dialog = page.getByRole('dialog', { name: 'El pedido anterior tiene descuentos' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Cargar sin descuentos' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(load).toBeFocused()
  await expect(page.getByLabel('Cantidad de Harina')).toHaveCount(0)
  await load.click()
  await page.screenshot({ path: testInfo.outputPath('previous-discounts-dialog.png'), fullPage: true })
  await dialog.getByRole('button', { name: 'Cargar sin descuentos' }).click()
  await expect(page.getByLabel('Cantidad de Harina')).toHaveValue('2')
  await expect(page.getByLabel('Descuento de Harina')).toHaveValue('0')
  await expect(page.getByLabel('Descuento general (%)')).toHaveValue('0')
  await expect(page.locator('.total-value')).toContainText('200,00')
  await load.click()
  await dialog.getByRole('button', { name: 'Copiar descuentos', exact: true }).click()
  await expect(page.getByLabel('Descuento de Harina')).toHaveValue('10')
  await expect(page.getByLabel('Descuento general (%)')).toHaveValue('5')
  await expect(page.locator('.total-value')).toContainText('171,00')
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
  await page.screenshot({ path: testInfo.outputPath('previous-order-loaded.png'), fullPage: true })
})

test('new orders select the customer list and confirm without collecting payment', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    const token = `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'ORDER_CREATE'] }))}.signature`
    sessionStorage.setItem('distribuidora.accessToken', token)
  })
  let confirmed: Record<string, unknown> | undefined
  let priceRequests = 0
  let releasePrice!: () => void
  const priceReady = new Promise<void>((resolve) => { releasePrice = resolve })
  const paged = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const url = new URL(route.request().url())
    let json: unknown = {}
    if (url.pathname === '/api/customers') json = paged([
      { id: 'customer-1', name: 'Almacén Norte', priceListId: 'list-2', sellerId: 'seller-1', seller: 'Lucía', balance: 1000 },
      { id: 'customer-2', name: 'Almacén Sur', priceListId: null, sellerId: 'seller-1', seller: 'Lucía', balance: 0 },
    ])
    if (url.pathname === '/api/products') json = paged([{ id: 'product-1', sku: 'HAR-1', name: 'Harina', presentation: 'Bolsa', status: 'ACTIVE', stock: 10 }])
    if (url.pathname === '/api/sellers') json = paged([{ id: 'seller-1', displayName: 'Lucía' }])
    if (url.pathname === '/api/pricing/lists') json = paged([
      { id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE' },
      { id: 'list-2', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' },
    ])
    if (url.pathname === '/api/pricing/resolve-batch') {
      priceRequests += 1
      await priceReady
      json = [{ productId: 'product-1', priceListId: url.searchParams.get('priceListId'), priceListCode: 'MAYORISTA', unitPrice: 150.5 }]
    }
    if (url.pathname === '/api/orders/confirm') {
      confirmed = route.request().postDataJSON()
      json = { orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-001', saleNumber: 'VEN-001', total: 202.5, paid: 0, balance: 202.5, previousBalanceAmount: 100, collectionTotal: 302.5 }
    }
    await route.fulfill({ json })
  })
  await page.goto('/orders/new')
  const emptyTable = page.locator('.order-lines')
  await expect(emptyTable.getByRole('table')).toBeVisible()
  await expect(emptyTable.getByRole('columnheader', { name: 'Producto', exact: true, includeHidden: true })).toHaveCount(1)
  await expect(emptyTable.locator('td')).toHaveAttribute('colspan', '6')
  await expect(emptyTable.getByText('Elegí un producto para consultar su precio en la lista seleccionada.')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('empty-order-table.png'), fullPage: true })
  await page.getByRole('combobox', { name: 'Cliente', exact: true }).fill('Almacén Sur')
  await page.getByRole('option', { name: 'Almacén Sur', exact: true }).click()
  await expect(page.getByLabel('Lista de precios', { exact: true })).toHaveValue('list-1')
  await page.getByRole('combobox', { name: 'Cliente', exact: true }).fill('Almacén Norte')
  await page.getByRole('option', { name: 'Almacén Norte', exact: true }).click()
  await expect(page.getByLabel('Lista de precios', { exact: true })).toHaveValue('list-2')
  await expect(page.getByRole('heading', { name: 'Cobro', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('Medio de pago')).toHaveCount(0)
  await page.getByRole('combobox', { name: 'Producto', exact: true }).selectOption('product-1')
  await expect(page.getByLabel('Precio', { exact: true })).toHaveAttribute('placeholder', 'Consultando...')
  await expect(page.getByRole('button', { name: 'Agregar producto', exact: true })).toBeDisabled()
  if (testInfo.project.name.startsWith('desktop')) {
    const boxes = await Promise.all([
      page.getByRole('combobox', { name: 'Producto', exact: true }),
      page.getByLabel('Precio', { exact: true }),
      page.getByLabel('Cantidad', { exact: true }),
      page.getByLabel('Descuento', { exact: true }),
      page.getByRole('button', { name: 'Agregar producto', exact: true }),
    ].map((control) => control.boundingBox()))
    const bottom = boxes[0]!.y + boxes[0]!.height
    for (const box of boxes) expect(Math.abs(box!.y + box!.height - bottom)).toBeLessThanOrEqual(1)
  }
  await page.screenshot({ path: testInfo.outputPath('price-loading.png'), fullPage: true })
  releasePrice()
  await expect(page.getByLabel('Precio', { exact: true })).toHaveValue('150,5')
  const requestsAfterLoading = priceRequests
  await page.getByRole('combobox', { name: 'Producto', exact: true }).selectOption('')
  await page.getByRole('combobox', { name: 'Producto', exact: true }).selectOption('product-1')
  await expect(page.getByLabel('Precio', { exact: true })).toHaveValue('150,5')
  expect(priceRequests).toBe(requestsAfterLoading)
  await page.getByLabel('Precio', { exact: true }).fill('125,00')
  await page.getByLabel('Cantidad', { exact: true }).fill('2')
  await page.getByLabel('Descuento', { exact: true }).fill('10')
  for (const name of ['Cantidad', 'Descuento']) {
    const input = page.getByLabel(name, { exact: true })
    await expect(input).toHaveAttribute('size', '4')
    const width = (await input.boundingBox())!.width
    expect(width).toBeLessThanOrEqual(60)
    expect(width).toBeGreaterThan(40)
  }
  if (testInfo.project.name.startsWith('desktop')) {
    const controls = [
      page.getByRole('combobox', { name: 'Producto', exact: true }),
      page.getByLabel('Precio', { exact: true }),
      page.getByLabel('Cantidad', { exact: true }),
      page.getByLabel('Descuento', { exact: true }),
      page.getByRole('button', { name: 'Agregar producto', exact: true }),
    ]
    const boxes = await Promise.all(controls.map((control) => control.boundingBox()))
    const bottom = boxes[0]!.y + boxes[0]!.height
    for (const box of boxes) expect(Math.abs(box!.y + box!.height - bottom)).toBeLessThanOrEqual(1)
  }
  await page.screenshot({ path: testInfo.outputPath('selected-product.png'), fullPage: true })
  await page.getByRole('button', { name: 'Agregar producto' }).click()
  await expect(emptyTable.locator('.table-empty-cell')).toHaveCount(0)
  expect(priceRequests).toBe(requestsAfterLoading)
  await expect(page.locator('.order-lines').getByRole('columnheader', { name: 'Precio unitario', includeHidden: true })).toHaveCount(1)
  await expect(page.getByLabel('Cantidad de Harina')).toHaveValue('2')
  await expect(page.getByLabel('Descuento de Harina')).toHaveValue('10')
  const removeButton = page.getByRole('button', { name: 'Quitar Harina' })
  await expect(removeButton.locator('svg')).toHaveAttribute('aria-hidden', 'true')
  const removeBox = (await removeButton.boundingBox())!
  expect(removeBox.width).toBe(38)
  expect(removeBox.height).toBe(38)
  if (testInfo.project.name.startsWith('desktop')) {
    const controls = [
      page.locator('.order-line-product'),
      page.getByLabel('Cantidad de Harina'),
      page.locator('.order-lines tbody td').nth(2),
      page.getByLabel('Descuento de Harina'),
      page.locator('.order-lines tbody td').nth(4),
      removeButton,
    ]
    const boxes = await Promise.all(controls.map((control) => control.boundingBox()))
    const middle = boxes[0]!.y + boxes[0]!.height / 2
    for (const box of boxes) expect(Math.abs(box!.y + box!.height / 2 - middle)).toBeLessThanOrEqual(1)
    for (let index = 1; index < boxes.length; index += 1) expect(boxes[index]!.x).toBeGreaterThan(boxes[index - 1]!.x)
  }
  await expect(page.locator('.total-value')).toContainText('225,00')
  await page.getByLabel('Descuento general (%)', { exact: true }).fill('10')
  await expect(page.locator('.total-value')).toContainText('202,50')
  await expect(page.locator('.order-account-balance')).toContainText('1.000,00')
  expect((await page.locator('.order-account').boundingBox())!.y).toBeGreaterThan((await page.locator('.order-lines').boundingBox())!.y)
  await page.getByRole('button', { name: 'Agregar saldo total' }).click()
  await expect(page.locator('.total-value')).toContainText('1.202,50')
  await expect(page.getByRole('button', { name: 'Quitar Saldo anterior' })).toHaveCount(1)
  await page.getByLabel('Importe para el remito', { exact: true }).fill('100')
  await page.getByRole('button', { name: 'Actualizar importe' }).click()
  await expect(page.locator('.total-value')).toContainText('302,50')
  await expect(page.getByRole('button', { name: 'Quitar Saldo anterior' })).toHaveCount(1)
  await page.getByRole('button', { name: 'Quitar Saldo anterior' }).click()
  await expect(page.locator('.total-value')).toContainText('202,50')
  await page.getByLabel('Importe para el remito', { exact: true }).fill('100')
  await page.getByRole('button', { name: 'Agregar importe' }).click()
  await expect(page.locator('.total-value')).toContainText('302,50')
  await expect(page.getByText('El importe queda pendiente en la cuenta corriente del cliente.')).toHaveCount(0)
  const totalBox = (await page.locator('.order-checkout-total').boundingBox())!
  const confirmBox = (await page.getByRole('button', { name: 'Confirmar pedido', exact: true }).boundingBox())!
  expect(Math.abs(confirmBox.x - totalBox.x)).toBeLessThanOrEqual(1)
  expect(Math.abs(confirmBox.width - totalBox.width)).toBeLessThanOrEqual(1)
  expect(confirmBox.y - (totalBox.y + totalBox.height)).toBeLessThanOrEqual(15)
  expect((await page.getByLabel('Descuento general (%)').boundingBox())!.width).toBeLessThanOrEqual(100)
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
  await page.screenshot({ path: testInfo.outputPath('new-order.png'), fullPage: true })
  await page.getByRole('button', { name: 'Confirmar pedido', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'PED-001' })).toBeVisible()
  expect(confirmed).toMatchObject({ customerId: 'customer-1', priceListId: 'list-2', orderDiscountPercent: 10, previousBalanceAmount: 100, payments: [], lines: [{ productId: 'product-1', quantity: 2, lineDiscountPercent: 10, unitPriceOverride: 125 }] })
  expect(confirmed!.lines).toHaveLength(1)
  await expect(page.getByText('Total a cobrar con la entrega', { exact: true })).toBeVisible()
})
