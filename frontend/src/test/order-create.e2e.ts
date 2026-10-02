import { expect, test, type Page } from '@playwright/test'

async function selectProduct(page: Page, search: string) {
  await page.getByRole('combobox', { name: 'Producto', exact: true }).fill(search)
  await page.getByRole('option', { name: search ? /^Harina$/ : 'Seleccionar producto...', exact: !search }).click()
}

test('editing uses the order form and locks customer and seller on desktop and mobile', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'ORDER_CREATE'] }))}.signature`)
  })
  let saved: Record<string, unknown> | undefined
  const detail = {
    order: { id: 'edit-1', number: 'PED-EDITAR', customerId: 'c1', customer: 'Almacén Norte', seller: 'Lucía', status: 'CONFIRMED', subtotal: 300, discount: 0, orderDiscountPercent: 0, total: 300, customerBalance: 1000, previousBalanceAmount: 20, date: '2026-10-01T15:00:00Z' },
    sale: { id: 'sale-1', number: 'VEN-EDITAR', status: 'CONFIRMED', total: 300, paid: 100, balance: 200 },
    items: [{ productId: 'p1', productName: 'Harina', quantity: 2, unitPrice: 150, lineTotal: 300, priceListId: 'list1', priceListCode: 'GENERAL', lineDiscountPercent: 0 }],
    payments: [{ id: 'pay1', method: 'CASH', amount: 100, date: '2026-10-01T15:00:00Z' }],
    account: { debit: 300, credit: 100, net: 200 }, deliveryAttempts: [],
  }
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    const paged = (content: unknown[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 })
    let json: unknown = paged([])
    if (path === '/api/orders') json = paged([{ id: 'edit-1', number: 'PED-EDITAR', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-10-01T15:00:00Z' }])
    if (path === '/api/orders/edit-1') {
      if (route.request().method() === 'PUT') saved = route.request().postDataJSON()
      json = detail
    }
    if (path === '/api/products') json = paged([{ id: 'p1', name: 'Harina', status: 'ACTIVE' }, { id: 'p2', name: 'Arroz', status: 'ACTIVE' }])
    if (path === '/api/pricing/lists') json = paged([{ id: 'list1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }])
    if (path === '/api/pricing/resolve-batch') json = [{ productId: 'p1', unitPrice: 200 }, { productId: 'p2', unitPrice: 50 }]
    if (path.endsWith('/last-order')) json = { available: false }
    await route.fulfill({ json })
  })
  await page.goto('/orders?dateMin=&dateMax=')
  const detailLink = page.getByRole('link', { name: 'Abrir pedido PED-EDITAR' })
  const editLink = page.getByRole('link', { name: 'Editar pedido PED-EDITAR' })
  await expect(detailLink).toBeVisible()
  await expect(editLink).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('order-list-actions.png'), fullPage: true })
  await editLink.click()
  await expect(page).toHaveURL(/\/orders\/edit-1\?edit=true$/)
  const customer = page.getByRole('combobox', { name: 'Cliente', exact: true })
  const seller = page.getByRole('combobox', { name: 'Vendedor', exact: true })
  await expect(customer).toBeDisabled()
  await expect(customer).toHaveValue('Almacén Norte')
  await expect(seller).toBeDisabled()
  await expect(seller).toHaveValue('Lucía')
  await expect(page.getByLabel('Fecha del pedido')).toHaveCount(0)
  const quantity = page.getByLabel('Cantidad de Harina')
  await quantity.fill('1.3')
  await expect(page.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled()
  await quantity.fill('3')
  await expect(page.getByLabel('Precio unitario de Harina')).toHaveValue('150')
  const product = page.getByRole('combobox', { name: 'Producto', exact: true })
  await product.fill('arr')
  await page.getByRole('option', { name: 'Arroz', exact: true }).click()
  await expect(page.getByLabel('Precio', { exact: true })).toHaveValue('50')
  await page.getByRole('button', { name: 'Agregar producto', exact: true }).click()
  await page.getByLabel('Descuento de Arroz').fill('10')
  await page.getByLabel('Importe para el remito').fill('40')
  await page.getByRole('button', { name: 'Actualizar importe', exact: true }).click()
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
  await page.screenshot({ path: testInfo.outputPath('edit-order.png'), fullPage: true })
  await page.getByRole('button', { name: 'Guardar cambios', exact: true }).click()
  await expect(page.getByText('Pedido actualizado. Se conservaron los cobros existentes.')).toBeVisible()
  expect(saved).toEqual({ priceListId: 'list1', lines: [{ productId: 'p1', quantity: 3, lineDiscountPercent: 0, unitPriceOverride: 150 }, { productId: 'p2', quantity: 1, lineDiscountPercent: 10 }], orderDiscountPercent: 0, previousBalanceAmount: 40 })
  await expect(page.getByRole('heading', { name: 'Pagos registrados' })).toBeVisible()
})

test('price tables show product names without a product code column', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
  })
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    const content = path === '/api/pricing/lists'
      ? [{ id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE', isDefault: true }]
      : path === '/api/pricing/lists/list-1/prices'
        ? [{ productId: 'product-1', name: 'Producto Demo 001', price: 100, effectiveOn: '2026-09-30' }]
        : []
    await route.fulfill({ json: { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 } })
  })
  await page.goto('/price-lists')
  await expect(page.getByText('Producto Demo 001', { exact: true })).toBeVisible()
  const table = page.locator('.price-products-table')
  await expect(table.getByRole('columnheader', { includeHidden: true })).toHaveCount(4)
  await expect(table.getByRole('columnheader', { name: 'SKU', includeHidden: true })).toHaveCount(0)
  await expect(table.getByRole('button', { name: 'Editar precio de Producto Demo 001' })).toBeVisible()
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
  if (testInfo.project.name.startsWith('desktop')) {
    expect(await table.getByRole('button', { name: 'Ver historial de precios de Producto Demo 001' }).evaluate((button) => {
      const box = button.getBoundingClientRect()
      return Boolean(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2)?.closest('button'))
    })).toBe(true)
  }
  await page.screenshot({ path: testInfo.outputPath('product-prices.png'), fullPage: true })
})

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
    if (path === '/api/products') json = paged([{ id: 'p1', name: 'Harina', presentation: 'Bolsa', status: 'ACTIVE' }])
    if (path === '/api/pricing/lists') json = paged([{ id: 'list1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }])
    if (path === '/api/sellers') json = paged([{ id: 'seller1', displayName: 'Lucía' }])
    if (path === '/api/pricing/resolve-batch') json = [{ productId: 'p1', priceListId: 'list1', priceListCode: 'GENERAL', unitPrice: 100 }]
    if (path === '/api/customers/c2/last-order') json = { available: false }
    if (path === '/api/customers/c1/last-order') json = { available: true, orderId: 'old', orderNumber: 'PED-ANTERIOR', orderDiscountPercent: 5, items: [{ productId: 'p1', productName: 'Harina', presentation: 'Bolsa', status: 'ACTIVE', quantity: 2, lineDiscountPercent: 10 }] }
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
  const lineQuantity = page.getByLabel('Cantidad de Harina')
  await lineQuantity.fill('1.3')
  await expect(lineQuantity).toHaveAttribute('aria-invalid', 'true')
  await expect(lineQuantity).toHaveAccessibleDescription('La cantidad de Harina debe ser positiva y múltiplo de 0,5.')
  await expect(page.getByRole('button', { name: 'Confirmar pedido', exact: true })).toBeDisabled()
  await expect(page.locator('.total-value')).toHaveText('—')
  await page.screenshot({ path: testInfo.outputPath('invalid-quantity.png'), fullPage: true })
  await lineQuantity.fill('1,5')
  await expect(lineQuantity).toHaveAttribute('aria-invalid', 'false')
  await expect(page.getByRole('button', { name: 'Confirmar pedido', exact: true })).toBeEnabled()
  await expect(page.locator('.total-value')).toContainText('128,25')
  await lineQuantity.fill('2')
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
    if (url.pathname === '/api/products') json = paged([{ id: 'product-1', name: 'Harina', presentation: 'Bolsa', status: 'ACTIVE' }, { id: 'product-2', name: 'Arroz', presentation: 'Bolsa', status: 'ACTIVE' }])
    if (url.pathname === '/api/sellers') json = paged([{ id: 'seller-1', displayName: 'Lucía' }])
    if (url.pathname === '/api/pricing/lists') json = paged([
      { id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE' },
      { id: 'list-2', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' },
    ])
    if (url.pathname === '/api/pricing/resolve-batch') {
      priceRequests += 1
      await priceReady
      json = url.searchParams.get('productIds')!.split(',').map((productId) => ({ productId, priceListId: url.searchParams.get('priceListId'), priceListCode: 'MAYORISTA', unitPrice: 150.5 }))
    }
    if (url.pathname === '/api/orders/confirm') {
      confirmed = route.request().postDataJSON()
      json = { orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-001', saleNumber: 'VEN-001', total: 202.5, paid: 0, balance: 202.5, previousBalanceAmount: 100, collectionTotal: 302.5 }
    }
    await route.fulfill({ json })
  })
  await page.goto('/orders/new')
  const emptyTable = page.locator('.order-lines')
  const orderDate = page.getByLabel('Fecha del pedido', { exact: true })
  const today = new Intl.DateTimeFormat('es-AR', { timeZone: 'America/Argentina/Buenos_Aires', day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date())
  await expect(orderDate).toHaveValue(today)
  await orderDate.click()
  const calendar = page.getByRole('dialog')
  await expect(calendar).toBeVisible()
  await expect(calendar.getByTitle('lunes', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('order-date-calendar.png'), fullPage: true })
  await page.keyboard.press('Escape')
  await orderDate.fill('29/09/2026')
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
  const productInput = page.getByRole('combobox', { name: 'Producto', exact: true })
  await productInput.click()
  await expect(page.getByRole('option', { name: /^Arroz$/ })).toBeVisible()
  await productInput.fill('har')
  const productOption = page.getByRole('option', { name: /^Harina$/ })
  await expect(productOption).toBeVisible()
  await expect(page.getByRole('option', { name: /^Arroz$/ })).toHaveCount(0)
  await productOption.scrollIntoViewIfNeeded()
  const optionBox = (await productOption.boundingBox())!
  expect(await page.evaluate(({ x, y }) => Boolean(document.elementFromPoint(x, y)?.closest('[role="option"]')), { x: optionBox.x + 12, y: optionBox.y + optionBox.height / 2 })).toBe(true)
  const widthsWhileOpen = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widthsWhileOpen.document).toBeLessThanOrEqual(widthsWhileOpen.viewport)
  await page.screenshot({ path: testInfo.outputPath('product-filter.png'), fullPage: true })
  await productInput.fill('harina')
  await productOption.click()
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
  await selectProduct(page, '')
  await selectProduct(page, 'harina')
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
  await expect(productInput).toHaveValue('harina')
  await expect(page.getByRole('button', { name: 'Agregar producto', exact: true })).toBeDisabled()
  await productInput.click()
  await expect(page.getByRole('option', { name: /^Arroz$/ })).toHaveCount(0)
  await expect(page.getByRole('option', { name: /^Harina$/ })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('retained-product-search.png'), fullPage: true })
  await page.keyboard.press('Escape')
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
  expect(confirmed).toMatchObject({ customerId: 'customer-1', orderDate: '2026-09-29', priceListId: 'list-2', orderDiscountPercent: 10, previousBalanceAmount: 100, payments: [], lines: [{ productId: 'product-1', quantity: 2, lineDiscountPercent: 10, unitPriceOverride: 125 }] })
  expect(confirmed!.lines).toHaveLength(1)
  await expect(page.getByText('Total a cobrar con la entrega', { exact: true })).toBeVisible()
})
