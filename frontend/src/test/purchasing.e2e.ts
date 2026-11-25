import { expect, test } from '@playwright/test'
import { analyticsReport } from './fixtures/analyticsDashboard'

test.beforeEach(async ({ page }) => {
  let purchaseStatus = 'PENDING'
  await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'STOCK_ADJUST', 'STOCK_SHRINKAGE'] }))}.signature`))
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const url = new URL(route.request().url())
    const pageNumber = Number(url.searchParams.get('page') ?? 0)
    const paged = (content: unknown[], totalPages = 1) => ({ content, page: pageNumber, size: 100, totalPages, totalElements: content.length * totalPages })
    if (url.pathname === '/api/suppliers') return route.fulfill({ json: paged([{ id: pageNumber ? 'supplier-a' : 'supplier-b', name: pageNumber ? 'Proveedor Norte' : 'Proveedor Sur' }], 2) })
    if (url.pathname === '/api/products') return route.fulfill({ json: paged([{ id: 'product-a', name: 'Molino Norte Harina', description: 'Harina', brandId: 'brand', category: 'Almacén', categoryId: 'category', cost: 20, stock: 1, status: 'ACTIVE', suppliers: [{ id: 'supplier-a', name: 'Proveedor Norte' }] }]) })
    if (url.pathname === '/api/pricing/lists') return route.fulfill({ json: paged([{ id: 'list', code: 'GENERAL', name: 'General', status: 'ACTIVE' }]) })
    if (url.pathname === '/api/categories') return route.fulfill({ json: [{ id: 'category', name: 'Almacén', status: 'ACTIVE' }] })
    if (url.pathname === '/api/brands') return route.fulfill({ json: [{ id: 'brand', name: 'Molino Norte', status: 'ACTIVE' }] })
    const order = { id: 'order-a', number: 'PRV-00000001', supplierId: 'supplier-a', supplier: 'Proveedor Norte', date: '2026-09-01', total: 75, status: purchaseStatus }
    const items = [{ productId: 'product-a', productName: 'Molino Norte Harina', quantity: 3, unitCost: 10, lineTotal: 30, currentCost: 25, status: 'ACTIVE' }]
    if (url.pathname.endsWith('/last-order')) return route.fulfill({ json: url.pathname.includes('supplier-a') ? { available: true, order, items } : { available: false } })
    if (url.pathname === '/api/supplier-orders' && route.request().method() === 'POST') return route.fulfill({ status: 201, json: order })
    if (url.pathname === '/api/supplier-orders') return route.fulfill({ json: paged([order]) })
    if (url.pathname === '/api/supplier-orders/order-a') return route.fulfill({ json: { order, items } })
    if (url.pathname === '/api/supplier-orders/order-a/confirm' && route.request().method() === 'POST') {
      purchaseStatus = 'CONFIRMED'
      return route.fulfill({ json: { order: { ...order, status: purchaseStatus }, items } })
    }
    if (url.pathname === '/api/supplier-orders/order-a/documents/a4') return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.7\n%%EOF' })
    if (route.request().method() === 'PUT') return route.fulfill({ status: 204 })
    return route.fulfill({ json: paged([]) })
  })
})

for (const width of [320, 760, 1440]) {
  test(`supplier requests, previous order and date filters work at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/supplier-orders')
    await expect(page.getByRole('heading', { name: 'Compras', exact: true })).toBeVisible()
    if (width <= 1050) await page.getByRole('button', { name: 'Abrir menú' }).click()
    const links = await page.getByRole('navigation').getByRole('link').allTextContents()
    expect(links.indexOf('Compras')).toBe(links.indexOf('Ventas') + 1)
    expect(links.indexOf('Clientes')).toBe(links.indexOf('Compras') + 1)
    if (width <= 1050) await page.keyboard.press('Escape')
    await page.getByRole('link', { name: '+ Nueva compra' }).click()
    await expect(page).toHaveURL(/\/supplier-orders\/new$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Nueva compra' })).toBeVisible()
    const purchase = page.getByRole('main').getByRole('form', { name: 'Nueva compra' })
    await expect(purchase).toBeVisible()
    const purchaseCalendarButton = purchase.getByRole('button', { name: 'Abrir calendario: Fecha de la compra' })
    await purchaseCalendarButton.click()
    const purchaseCalendar = page.getByRole('dialog', { name: /Calendario: Fecha de la compra/ })
    await expect(purchaseCalendar).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(purchaseCalendar).toHaveCount(0)
    await expect(purchaseCalendarButton).toBeFocused()
    const supplier = purchase.getByRole('combobox', { name: 'Proveedor', exact: true })
    await expect(supplier).toBeEnabled()
    await supplier.fill('Norte')
    await page.getByRole('option', { name: 'Proveedor Norte', exact: true }).click()
    const previous = page.getByRole('button', { name: 'Cargar compra anterior' })
    await expect(previous).toBeEnabled()
    await previous.click()
    await expect(page.getByLabel('Cantidad de Molino Norte Harina')).toHaveValue('3')
    const unitCost = purchase.getByLabel('Costo de Molino Norte Harina')
    await expect(unitCost).toHaveValue('25')
    await unitCost.fill('27,125')
    await expect(purchase.getByText(/Subtotal|Total estimado/)).toHaveCount(0)
    await expect(purchase.getByRole('button', { name: 'Confirmar compra' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Guardar compra' })).toBeEnabled()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`purchase-form-${width}.png`), fullPage: true })
    const request = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/supplier-orders')
    await page.getByRole('button', { name: 'Guardar compra' }).click()
    const body = (await request).postDataJSON()
    expect(body).toMatchObject({ supplierId: 'supplier-a' })
    expect(body).not.toHaveProperty('confirm')
    expect(body.lines).toEqual([{ productId: 'product-a', quantity: 3, unitCost: 27.125 }])
    await expect(page.getByText('Compra PRV-00000001 guardada como pendiente.', { exact: false })).toBeVisible()
    await expect(page).toHaveURL(/\/supplier-orders$/)
    await expect(purchase).toHaveCount(0)
    await page.getByRole('button', { name: 'Abrir calendario: Desde' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Abrir calendario: Desde' })).toBeFocused()
    await page.getByRole('link', { name: 'Ver compra PRV-00000001', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'PRV-00000001' })).toBeVisible()
    await expect(page.getByText('Pendiente', { exact: true })).toBeVisible()
    const downloadButton = page.getByRole('button', { name: 'Descargar PDF' })
    await expect(downloadButton).toBeVisible()
    const downloadBounds = (await downloadButton.boundingBox())!
    const tableBounds = (await page.locator('.table-wrap').boundingBox())!
    const cancelBounds = (await page.getByRole('button', { name: 'Anular compra' }).boundingBox())!
    expect(downloadBounds.x).toBeCloseTo(tableBounds.x, 0)
    expect(cancelBounds.x + cancelBounds.width).toBeCloseTo(tableBounds.x + tableBounds.width, 0)
    if (width > 320) {
      const editBounds = (await page.getByRole('button', { name: 'Editar compra' }).boundingBox())!
      expect(editBounds.x).toBeGreaterThan(downloadBounds.x)
      expect(downloadBounds.y).toBeCloseTo(editBounds.y, 0)
      expect(downloadBounds.y).toBeCloseTo(cancelBounds.y, 0)
    }
    await page.screenshot({ path: info.outputPath(`purchase-detail-${width}.png`), fullPage: true })
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      downloadButton.click(),
    ])
    expect(download.suggestedFilename()).toBe('compra-PRV-00000001.pdf')
    const confirmPurchase = page.getByRole('button', { name: 'Confirmar compra', exact: true })
    await confirmPurchase.press('Enter')
    const confirmation = page.getByRole('dialog', { name: /Confirmar la compra/ })
    await expect(confirmation).toContainText('se van a sumar al stock')
    await expect(confirmation.getByRole('button', { name: 'Aceptar' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(confirmation).toHaveCount(0)
    await expect(confirmPurchase).toBeFocused()
    await expect(page.getByText('Pendiente', { exact: true })).toBeVisible()
    await page.keyboard.press('Enter')
    await expect(confirmation.getByRole('button', { name: 'Aceptar' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByText('Confirmada', { exact: true })).toBeVisible()
    await expect(page.getByRole('status')).toContainText('las cantidades se sumaron al stock')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
  test(`optional product supplier associations remain usable at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/products')
    const row = page.locator('.data-table tbody tr').filter({ hasText: 'Molino Norte Harina' })
    await expect(row.locator('td').nth(0)).toHaveText('Molino Norte Harina')
    await expect(row.locator('td').nth(1)).toHaveText('Molino Norte')
    await expect(row.locator('td').nth(2)).toHaveText('Almacén')
    if (width === 1440) {
      await expect(row.locator('td[data-label="Estado"]')).toHaveText('Activo')
      const actions = row.locator('.table-row-actions > .button, .secondary-row-actions > summary')
      await expect(actions).toHaveCount(2)
      const centers = await actions.evaluateAll((buttons) => buttons.map((button) => {
        const bounds = button.getBoundingClientRect()
        return bounds.top + bounds.height / 2
      }))
      for (const center of centers) expect(center).toBeCloseTo(centers[0], 0)
      const tableFits = await page.locator('.stock-list-table').evaluate((table) => table.scrollWidth <= table.clientWidth + 1)
      expect(tableFits).toBe(true)
      await page.screenshot({ path: info.outputPath('stock-desktop.png') })
    }
    await page.getByRole('button', { name: 'Editar Molino Norte Harina', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Quitar proveedor Proveedor Norte' })).toBeVisible()
    const suppliers = page.getByRole('combobox', { name: 'Proveedores (opcional)' })
    await expect(suppliers).toBeEnabled()
    await suppliers.fill('Sur')
    await page.getByRole('option', { name: 'Proveedor Sur' }).click()
    await page.getByRole('button', { name: 'Quitar proveedor Proveedor Norte' }).click()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`product-suppliers-${width}.png`), fullPage: true })
    const request = page.waitForRequest((request) => request.method() === 'PUT')
    await page.getByRole('button', { name: 'Guardar cambios', exact: true }).click()
    expect((await request).postDataJSON().supplierIds).toEqual(['supplier-b'])
  })
}

test('closing a new purchase with changes asks before discarding them', async ({ page }) => {
  await page.goto('/supplier-orders?supplierId=supplier-a&page=2')
  await page.getByRole('link', { name: '+ Nueva compra' }).click()
  await expect(page).toHaveURL(/\/supplier-orders\/new\?supplierId=supplier-a&page=2$/)
  const purchase = page.getByRole('form', { name: 'Nueva compra' })
  await purchase.getByRole('combobox', { name: 'Proveedor', exact: true }).fill('Norte')
  await page.getByRole('option', { name: 'Proveedor Norte' }).click()

  await purchase.getByRole('button', { name: 'Cancelar', exact: true }).click()
  const discard = page.getByRole('dialog', { name: '¿Descartar los cambios?' })
  await discard.getByRole('button', { name: 'Seguir editando' }).click()
  await expect(purchase.getByRole('combobox', { name: 'Proveedor', exact: true })).toHaveValue('Proveedor Norte')
  await purchase.getByRole('button', { name: 'Cancelar', exact: true }).click()
  await discard.getByRole('button', { name: 'Descartar cambios' }).click()
  await expect(purchase).toHaveCount(0)
  await expect(page).toHaveURL(/\/supplier-orders\?supplierId=supplier-a&page=2$/)
})

for (const width of [320, 760, 1440]) {
  test(`starts replenishment with fresh stock at ${width}px`, async ({ page }, info) => {
    const fromDashboard = width === 1440
    const askSupplier = width !== 320
    const productName = 'Harina para reponer'
    const north = { id: 'supplier-a', name: 'Proveedor Norte' }
    const south = { id: 'supplier-b', name: 'Proveedor Sur' }
    const currentStock = fromDashboard ? 2 : -2.5
    const expectedQuantity = fromDashboard ? 1 : 3.5
    let optionsAvailable = width !== 320
    const writes: Array<{ supplierId: string; lines: Array<{ productId: string; quantity: number; unitCost: number }> }> = []
    await page.setViewportSize({ width, height: 900 })
    await page.route((url) => url.pathname === '/api/dashboard/analytics', (route) => route.fulfill({ json: {
      ...analyticsReport, stockCoverage: [{ id: 'restock-product', name: productName, stock: 1, dailyUnits: 3, days: 0.3 }],
    } }))
    await page.route((url) => url.pathname === '/api/products', (route) => {
      const params = new URL(route.request().url()).searchParams
      const forPurchase = params.get('includeStock') === 'true'
      const secondPage = params.get('page') === '1'
      if (forPurchase && !optionsAvailable) {
        return route.fulfill({ status: 503, json: { detail: 'Catálogo no disponible' } })
      }
      const restock = {
        id: 'restock-product', name: productName, stock: forPurchase ? currentStock : -10,
        category: 'Almacén', cost: forPurchase ? 25.5 : 20, status: 'ACTIVE',
        suppliers: fromDashboard ? [] : askSupplier ? [north, south] : [north],
      }
      const stocked = { id: 'stocked-product', name: 'Producto con stock', stock: 4, cost: 10, status: 'ACTIVE' }
      const inactive = { id: 'inactive-product', name: 'Producto inactivo', stock: 0, cost: 10, status: 'INACTIVE' }
      const content = !forPurchase ? [restock, stocked, inactive] : secondPage ? [restock] : [stocked, inactive]
      return route.fulfill({ json: { content, page: secondPage ? 1 : 0, size: 100, totalElements: 3, totalPages: forPurchase ? 2 : 1 } })
    })
    await page.route((url) => url.pathname === '/api/supplier-orders', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      const body = route.request().postDataJSON()
      writes.push(body)
      return route.fulfill({ status: 201, json: {
        id: 'restock-order', number: 'PRV-00000002', total: 25.5 * body.lines[0].quantity, status: 'PENDING',
      } })
    })
    const source = fromDashboard ? '/analytics?period=custom&dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026' : '/products?search=Harina&stock=NEGATIVE'
    await page.goto(source)
    if (!fromDashboard) {
      await page.getByLabel('Más acciones de ' + productName).click()
      await expect(page.getByRole('link', { name: 'Reponer Producto con stock', includeHidden: true })).toHaveCount(0)
      await expect(page.getByRole('link', { name: 'Reponer Producto inactivo', includeHidden: true })).toHaveCount(0)
    }
    await page.getByRole('link', { name: `Reponer ${productName}` }).click()
    await expect(page.getByRole('heading', { name: 'Reposición' })).toBeVisible()
    if (width === 320) {
      await expect(page.getByRole('alert')).toContainText('No se pudo consultar el producto.')
      optionsAvailable = true
      await page.getByRole('button', { name: 'Reintentar productos' }).click()
    }
    const supplier = page.getByRole('combobox', { name: 'Proveedor', exact: true })
    const quantity = page.getByLabel(`Cantidad de ${productName}`)
    await expect(quantity).toHaveValue(String(expectedQuantity).replace('.', ','))
    await expect(page.getByLabel(`Costo de ${productName}`)).toHaveValue('25,5')
    expect(writes).toHaveLength(0)
    if (askSupplier) {
      await expect(supplier).toHaveValue('Seleccionar proveedor...')
      await expect(page.getByRole('button', { name: 'Guardar compra' })).toBeDisabled()
      if (!fromDashboard) {
        const associations = page.getByText(/Proveedores asociados/)
        await expect(associations).toContainText('Proveedor Norte')
        await expect(associations).toContainText('Proveedor Sur')
      }
      await supplier.fill('Sur')
      await page.getByRole('option', { name: 'Proveedor Sur', exact: true }).click()
    } else {
      await expect(supplier).toHaveValue('Proveedor Norte')
    }
    if (width === 760) {
      await quantity.fill('4')
      await page.getByRole('button', { name: 'Cancelar', exact: true }).click()
      await page.getByRole('button', { name: 'Seguir editando' }).click()
      await expect(quantity).toHaveValue('4')
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`reposicion-${width}.png`), fullPage: true })
    await expect(page.getByRole('button', { name: 'Confirmar compra' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Guardar compra' }).click()
    await expect(page.getByRole('status')).toContainText('Compra guardada como pendiente')
    expect(writes).toHaveLength(1)
    expect(writes[0]).toMatchObject({ supplierId: askSupplier ? 'supplier-b' : 'supplier-a' })
    expect(writes[0]).not.toHaveProperty('confirm')
    expect(writes[0].lines).toEqual([{ productId: 'restock-product', quantity: width === 760 ? 4 : expectedQuantity, unitCost: 25.5 }])
    await expect(page.getByRole('link', { name: 'Ver compra' })).toHaveAttribute('href', '/supplier-orders/restock-order')
    await page.getByRole('button', { name: fromDashboard ? 'Volver a Dashboard' : 'Volver a Stock' }).click()
    await expect(page).toHaveURL(new RegExp(source.replace('?', '\\?')))
  })
}

test('blocks a stale restock link to an inactive product and returns to the original stock filter', async ({ page }) => {
  const writes: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/supplier-orders') writes.push(request.url())
  })
  await page.route((url) => url.pathname === '/api/products', (route) => route.fulfill({ json: {
    content: [{ id: 'product-a', name: 'Harina desactivada', status: 'INACTIVE', stock: 0, cost: 20 }],
    page: 0, size: 100, totalElements: 1, totalPages: 1,
  } }))
  const params = new URLSearchParams({ productId: 'product-a', source: 'stock', filters: 'stock=NEGATIVE' })
  await page.goto(`/supplier-orders/restock?${params}`)
  await expect(page.getByRole('alert')).toContainText('El producto ya no está disponible para reposición.')
  await expect(page.getByRole('button', { name: 'Guardar compra' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Volver', exact: true }).click()
  await expect(page).toHaveURL(/\/products\?stock=NEGATIVE$/)
  expect(writes).toHaveLength(0)
})
