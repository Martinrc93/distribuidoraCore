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
  test(`price list actions stay visible and readable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 800 })
    await mockApi(page)
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
