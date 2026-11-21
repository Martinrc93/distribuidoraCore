import { expect, test, type Page } from '@playwright/test'

const products = Array.from({ length: 20 }, (_, index) => ({
  id: `product-${index + 1}`, name: `Marca Demo Producto ${String(index + 1).padStart(2, '0')}`, description: `Producto ${index + 1}`,
  brandId: 'brand-1', categoryId: 'category-1', category: 'Almacén', cost: 100, stock: 10 + index, status: 'ACTIVE', suppliers: [],
}))

async function mockApi(page: Page) {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'STOCK_ADJUST'] }))}.signature`)
  })
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/products') return route.fulfill({ json: { content: products, page: 0, size: 20, totalElements: products.length, totalPages: 1 } })
    if (path === '/api/brands') return route.fulfill({ json: [{ id: 'brand-1', name: 'Marca Demo', status: 'ACTIVE' }] })
    if (path === '/api/categories') return route.fulfill({ json: [{ id: 'category-1', name: 'Almacén', status: 'ACTIVE' }] })
    if (path === '/api/pricing/lists') return route.fulfill({ json: { content: [{ id: 'list-1', code: 'GENERAL', name: 'Lista general', status: 'ACTIVE', isDefault: true }], page: 0, size: 20, totalElements: 1, totalPages: 1 } })
    return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
  })
}

async function expectInsideViewport(page: Page, locator: ReturnType<Page['locator']>) {
  const box = await locator.boundingBox()
  const viewport = page.viewportSize()!
  expect(box).not.toBeNull()
  expect(box!.y).toBeGreaterThanOrEqual(0)
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height + 1)
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width + 1)
}

for (const viewport of [{ width: 1280, height: 720 }, { width: 390, height: 844 }]) {
  for (const textSize of ['normal', 'large'] as const) {
    test(`product form opens inside the viewport after scrolling (${viewport.width}px, ${textSize} text)`, async ({ page }, testInfo) => {
      await page.setViewportSize(viewport)
      await mockApi(page)
      if (textSize === 'large') await page.addInitScript(() => localStorage.setItem('distribuidora.text-size', 'large'))
      await page.goto('/products')
      const lastEdit = page.getByRole('button', { name: `Editar ${products.at(-1)!.name}` })
      await lastEdit.scrollIntoViewIfNeeded()
      expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width)
      await lastEdit.click()
      const dialog = page.getByRole('dialog', { name: 'Editar producto' })
      await expect(dialog).toBeVisible()
      await expectInsideViewport(page, dialog)
      await expect(dialog.getByLabel('Descripción')).toBeFocused()
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await dialog.getByRole('button', { name: 'Guardar cambios' }).scrollIntoViewIfNeeded()
      await expect(dialog.getByRole('button', { name: 'Guardar cambios' })).toBeInViewport()
      await page.screenshot({ path: testInfo.outputPath(`product-form-${viewport.width}-${textSize}.png`) })
      await page.keyboard.press('Escape')
      await expect(dialog).toHaveCount(0)
      await expect(lastEdit).toBeFocused()
      expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
    })
  }
}

test('text size preference toggles from the top bar and persists after reload', async ({ page }) => {
  await mockApi(page)
  await page.goto('/products')
  await page.getByRole('button', { name: 'Agrandar el texto' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-text-size', 'large')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-text-size', 'large')
  await page.getByRole('button', { name: 'Usar texto de tamaño normal' }).click()
  await expect(page.locator('html')).not.toHaveAttribute('data-text-size')
})

for (const width of [1280, 390]) {
  test(`new price list uses only a name with aligned actions at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 800 })
    await mockApi(page)
    await page.addInitScript(() => localStorage.setItem('distribuidora.theme', 'dark'))
    await page.route('**/api/pricing/lists/creation-preview', (route) => route.fulfill({ json: { productsToCopy: 1 } }))
    await page.route('**/api/pricing/lists', (route) => route.request().method() === 'POST'
      ? route.fulfill({ status: 201, json: { id: 'list-2' } }) : route.fallback())
    await page.goto('/price-lists')
    const open = page.getByRole('button', { name: '+ Nueva lista' })
    const dialog = page.getByRole('dialog', { name: 'Nueva lista de precios' })
    const name = dialog.getByRole('textbox', { name: 'Nombre de lista' })
    const cancel = dialog.getByRole('button', { name: 'Cancelar' })
    const save = dialog.getByRole('button', { name: 'Guardar lista' })
    await open.click()
    await expect(dialog.getByRole('textbox')).toHaveCount(1)
    await expect(name).toBeFocused()
    await expect(save).toBeDisabled()
    await name.fill('Promoción')
    const cancelBounds = (await cancel.boundingBox())!
    const saveBounds = (await save.boundingBox())!
    expect(saveBounds.y).toBeCloseTo(cancelBounds.y, 0)
    expect(saveBounds.x).toBeGreaterThanOrEqual(cancelBounds.x + cancelBounds.width)
    await expectInsideViewport(page, dialog)
    await page.screenshot({ path: testInfo.outputPath(`new-price-list-${width}.png`) })

    await cancel.click()
    await expect(dialog).toHaveCount(0)
    await expect(open).toBeFocused()
    await open.click()
    await expect(name).toHaveValue('')
    await name.fill('Promoción')
    await save.click()
    const confirmation = page.getByRole('dialog', { name: /¿Crear la lista con los precios/ })
    await expect(confirmation).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(confirmation).toHaveCount(0)
    await expect(name).toHaveValue('Promoción')

    await save.click()
    const posted = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/pricing/lists')
    await confirmation.getByRole('button', { name: 'Crear lista' }).click()
    expect((await posted).postDataJSON()).toEqual({ name: 'Promoción' })
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('status')).toContainText('Lista creada')
  })

  test(`price list actions stay visible and readable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 800 })
    await mockApi(page)
    if (width === 1280) await page.addInitScript(() => localStorage.setItem('distribuidora.theme', 'dark'))
    await page.route(/\/api\/pricing\/lists\/list-1\/prices/, (route) => route.fulfill({ json: {
      content: products.slice(0, 3).map((product) => ({ productId: product.id, name: product.name, price: 150.5, effectiveOn: '2026-11-01' })),
      page: 0, size: 20, totalElements: 3, totalPages: 1,
    } }))
    await page.goto('/price-lists')
    const edit = page.getByRole('button', { name: `Programar precio de ${products[0].name}` })
    const history = page.getByRole('button', { name: `Ver historial de precios de ${products[0].name}` })
    await expect(edit).toHaveText('Programar')
    await expect(history).toHaveText('Ver historial')
    for (const button of [edit, history]) {
      await button.scrollIntoViewIfNeeded()
      expect(await button.evaluate((element) => {
        const box = element.getBoundingClientRect()
        return Boolean(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2)?.closest('button') === element)
      })).toBe(true)
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: testInfo.outputPath(`price-list-${width}.png`) })

    await edit.click()
    const dialog = page.getByRole('dialog', { name: 'Programar precio' })
    const price = dialog.getByRole('textbox', { name: `Precio de ${products[0].name}` })
    const date = dialog.getByLabel('Fecha de vigencia')
    await expect(dialog.getByText(`${products[0].name} · Lista general`)).toBeVisible()
    await expect(price).toBeFocused()
    await expectInsideViewport(page, dialog)
    const priceBox = (await price.boundingBox())!
    const dateBox = (await date.boundingBox())!
    expect(dateBox.y).toBeGreaterThan(priceBox.y + priceBox.height)
    expect(dateBox.x).toBeCloseTo(priceBox.x)
    expect(dateBox.width).toBeCloseTo(priceBox.width)
    await expect(dialog.getByRole('button', { name: 'Guardar precio' })).toBeInViewport()
    await page.screenshot({ path: testInfo.outputPath(`schedule-price-${width}.png`) })

    await date.fill('2026-12-01')
    await page.keyboard.press('Escape')
    const discard = page.getByRole('dialog', { name: '¿Descartar los cambios?' })
    await discard.getByRole('button', { name: 'Seguir editando' }).click()
    await expect(date).toHaveValue('2026-12-01')
    await dialog.getByRole('button', { name: 'Cancelar', exact: true }).click()
    await discard.getByRole('button', { name: 'Descartar cambios' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(edit).toBeFocused()
  })
}

for (const width of [1280, 680, 390]) {
  test(`price history fits its modal at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 800 })
    await mockApi(page)
    if (width === 1280) await page.addInitScript(() => localStorage.setItem('distribuidora.theme', 'dark'))
    if (width === 390) await page.addInitScript(() => localStorage.setItem('distribuidora.text-size', 'large'))
    const product = products[0]
    const priceHistory = [{ effectiveOn: '2026-11-01', price: 150.5, scheduled: false }, { effectiveOn: '2026-12-01', price: 165, scheduled: true }]
    await page.route('**/api/pricing/lists/list-1/prices?*', (route) => route.fulfill({ json: {
      content: [{ productId: product.id, name: product.name, price: 150.5, effectiveOn: '2026-11-01' }], totalElements: 1, totalPages: 1,
    } }))
    await page.route(`**/api/pricing/lists/list-1/products/${product.id}/history?*`, (route) => route.fulfill({ json: {
      content: priceHistory.map((entry) => ({ ...entry, recordedAt: '2026-11-01T14:00:00Z', updatedAt: '2026-11-03T17:00:00Z' })), totalElements: 2, totalPages: 1,
    } }))
    await page.goto('/price-lists')
    const trigger = page.getByRole('button', { name: `Ver historial de precios de ${product.name}` })
    if (width === 1280) {
      const heading = page.getByRole('columnheader', { name: 'Precio de lista' })
      await expect(heading).toHaveCSS('text-transform', 'none')
      await expect(heading).toHaveCSS('padding-top', '13px')
    }
    await trigger.click()
    const dialog = page.getByRole('dialog', { name: 'Historial de precios' })
    const close = dialog.getByRole('button', { name: 'Cerrar historial' })
    const cancel = dialog.getByRole('button', { name: 'Cancelar precio del 2026-12-01' })
    await expect(dialog).toHaveAccessibleDescription(`${product.name} · Lista general`)
    await expect(close).toBeFocused()
    await expect(cancel).toBeVisible()
    await expectInsideViewport(page, dialog)
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    if (width > 640) {
      await expect(dialog.getByRole('columnheader', { name: 'Registrado el' })).toBeInViewport()
      await expect(dialog.getByRole('columnheader', { name: 'Acciones' })).toBeInViewport()
      const wrapper = dialog.locator('.price-history-table')
      expect(await wrapper.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    } else {
      await expect(dialog.locator('tbody tr').first()).toHaveCSS('display', 'block')
    }
    await page.screenshot({ path: testInfo.outputPath(`price-history-${width}.png`) })

    await cancel.click()
    const confirmation = page.getByRole('dialog', { name: '¿Cancelar el precio programado para 2026-12-01?' })
    await expect(confirmation.getByRole('button', { name: 'Volver' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(cancel).toBeFocused()
    await expect(close).toBeInViewport()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(trigger).toBeFocused()
  })
}

for (const width of [1280, 390]) {
  test(`price list follow states and default-price warning at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 800 })
    await mockApi(page)
    const listsBody = { content: [
      { id: 'list-1', code: 'GENERAL', name: 'Lista general', status: 'ACTIVE', isDefault: true, pendingReviewCount: 0 },
      { id: 'list-4', code: 'LISTA_4', name: 'Lista 4', status: 'ACTIVE', isDefault: false, pendingReviewCount: 2 },
    ], page: 0, size: 20, totalElements: 10, totalPages: 1 }
    await page.route(/\/api\/pricing\/lists\?/, (route) => route.fulfill({ json: listsBody }))
    await page.route(/\/api\/pricing\/lists\/list-4\/prices/, (route) => route.fulfill({ json: {
      content: products.slice(0, 3).map((product, index) => ({ productId: product.id, name: product.name, price: 150.5, effectiveOn: '2026-11-01',
        followStatus: index === 0 ? 'PENDING' : index === 1 ? 'CONFIRMED' : null })),
      page: 0, size: 20, totalElements: 3, totalPages: 1,
    } }))
    await page.route(/\/api\/pricing\/lists\/list-1\/prices/, (route) => route.fulfill({ json: {
      content: [{ productId: products[0].id, name: products[0].name, price: 150.5, effectiveOn: '2026-11-01' }], page: 0, size: 20, totalElements: 1, totalPages: 1,
    } }))
    await page.route(/\/api\/pricing\/lists\/products\/.*\/followers/, (route) => route.fulfill({ json: [{ priceListId: 'list-4', code: 'LISTA_4', name: 'Lista 4' }] }))
    await page.goto('/price-lists')
    await expect(page.getByRole('button', { name: '+ Nueva lista' })).toBeDisabled()
    await page.getByRole('tab', { name: /Lista 4/ }).click()
    await expect(page.getByRole('tab', { name: /Lista 4/ })).toContainText('2 sin revisar')
    await expect(page.getByRole('button', { name: 'Confirmar 2 sin revisar' })).toBeVisible()
    await expect(page.getByRole('button', { name: `Confirmar precio de ${products[0].name} sin cambios` })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: testInfo.outputPath(`follow-states-${width}.png`), fullPage: true })

    await page.getByRole('tab', { name: /Lista general/ }).click()
    await page.getByRole('button', { name: `Programar precio de ${products[0].name}` }).click()
    await page.getByRole('textbox', { name: `Precio de ${products[0].name}`, exact: true }).fill('160')
    await page.getByRole('button', { name: 'Guardar precio' }).click()
    const warning = page.getByRole('dialog', { name: 'Este cambio también se aplica en Lista 4' })
    await expect(warning).toBeVisible()
    await expect(warning.getByRole('button', { name: 'Volver', exact: true })).toBeFocused()
    await page.screenshot({ path: testInfo.outputPath(`follow-warning-${width}.png`) })
    await page.keyboard.press('Escape')
    await expect(warning).toHaveCount(0)
  })
}

test('downloads all lists or the selected full list independently of product filters', async ({ page }) => {
  await mockApi(page)
  await page.addInitScript(() => localStorage.setItem('distribuidora.text-size', 'large'))
  await page.route('**/api/pricing/lists?*', (route) => route.fulfill({ json: {
    content: [
      { id: 'list-1', code: 'GENERAL', name: 'Lista general', status: 'ACTIVE', isDefault: true },
      { id: 'list-2', code: 'PROMO', name: 'Lista promociones', status: 'INACTIVE', isDefault: false },
    ], page: 0, size: 20, totalElements: 2, totalPages: 1,
  } }))
  const requested: string[] = []
  await page.route(/\/api\/documents\/price-lists(?:[/?]|$)/, (route) => {
    const url = new URL(route.request().url())
    requested.push(url.pathname + url.search)
    return route.fulfill({ contentType: 'application/pdf', body: Buffer.from('%PDF-1.4\n%%EOF\n') })
  })
  await page.goto('/price-lists?categoryId=category-1')
  await page.getByRole('tab', { name: /Lista promociones/i }).click()
  await expect(page.getByRole('button', { name: 'Descargar Lista promociones' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width)

  for (const [buttonName, filename] of [
    ['Descargar todas las listas', 'listas-de-precios.pdf'],
    ['Descargar Lista promociones', 'lista-PROMO.pdf'],
    ['Descargar PDF filtrado', 'lista-PROMO.pdf'],
  ]) {
    const download = page.waitForEvent('download')
    await page.getByRole('button', { name: buttonName, exact: true }).click()
    expect((await download).suggestedFilename()).toBe(filename)
  }
  expect(requested).toEqual([
    '/api/documents/price-lists',
    '/api/documents/price-lists/list-2',
    '/api/documents/price-lists/list-2?categoryId=category-1',
  ])
})

test('prints the filtered price list without downloading it', async ({ page }) => {
  await mockApi(page)
  let requested = ''
  await page.route(/\/api\/documents\/price-lists\//, (route) => {
    requested = route.request().url()
    return route.fulfill({ status: 200, contentType: 'application/pdf', body: Buffer.from('%PDF-1.4\n%%EOF\n') })
  })
  await page.route(/\/api\/pricing\/lists\/list-1\/prices/, (route) => route.fulfill({ json: {
    content: [{ productId: products[0].id, name: products[0].name, price: 150.5, effectiveOn: '2026-11-01' }], page: 0, size: 20, totalElements: 1, totalPages: 1,
  } }))
  await page.goto('/price-lists?categoryId=category-1')
  let downloaded = false
  page.on('download', () => { downloaded = true })
  await page.getByRole('button', { name: 'Imprimir lista' }).click()
  await expect(page.locator('iframe[title="Documento para imprimir"]')).toHaveAttribute('src', /^blob:/)
  await expect(page.getByRole('status')).toHaveText('Documento enviado a imprimir.')
  expect(new URL(requested).searchParams.get('categoryId')).toBe('category-1')
  expect(downloaded).toBe(false)
})
