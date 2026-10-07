import { expect, test } from '@playwright/test'

for (const width of [390, 1280]) {
  test(`product form generates its name and requires a brand at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    await page.addInitScript(() => {
      sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    })
    let created: Record<string, unknown> | undefined
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/brands') return route.fulfill({ json: [{ id: 'brand-1', name: 'Molino Norte', status: 'ACTIVE' }] })
      if (path === '/api/categories') return route.fulfill({ json: [{ id: 'category-1', name: 'Almacén', status: 'ACTIVE' }] })
      if (path === '/api/products' && request.method() === 'POST') {
        created = request.postDataJSON()
        return route.fulfill({ status: 201, json: { id: 'product-1' } })
      }
      if (path === '/api/pricing/lists') return route.fulfill({ json: { content: [{ id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 } })
      return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
    })
    await page.goto('/products')
    await page.getByRole('button', { name: '+ Nuevo producto' }).first().click()
    await page.getByLabel('Descripción', { exact: true }).fill('Harina 1 kg')
    await page.getByRole('combobox', { name: 'Categoría', exact: true }).selectOption('category-1')
    await page.getByLabel('Costo', { exact: true }).fill('10')
    await page.getByLabel('Precio para GENERAL', { exact: true }).fill('15')
    await page.getByRole('button', { name: 'Guardar producto', exact: true }).click()
    expect(created).toBeUndefined()
    await expect(page.getByRole('combobox', { name: 'Marca', exact: true })).toBeFocused()
    await page.getByRole('combobox', { name: 'Marca', exact: true }).selectOption('brand-1')
    await expect(page.getByLabel('Nombre', { exact: true })).toHaveText('Molino Norte Harina 1 kg')
    await page.getByLabel('Descripción', { exact: true }).fill('x'.repeat(200))
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.getByLabel('Descripción', { exact: true }).fill('Harina 1 kg')
    await page.screenshot({ path: testInfo.outputPath(`product-form-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: 'Guardar producto', exact: true }).click()
    await expect(page.getByText('Producto creado correctamente.', { exact: true })).toBeVisible()
    expect(created).toEqual({ description: 'Harina 1 kg', categoryId: 'category-1', brandId: 'brand-1', cost: 10, prices: [{ priceListId: 'list-1', price: 15 }] })
  })
}
