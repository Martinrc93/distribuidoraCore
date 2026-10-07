import { expect, test } from '@playwright/test'

for (const width of [320, 640, 760, 1280]) {
  test(`supplier creation, editing and keyboard access at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 800 })
    await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`))
    const suppliers = [{ id: 'supplier-1', name: 'North supplier', phone: '123', email: 'contact@example.test', address: 'Long address '.repeat(35) }]
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      if (request.method() === 'POST') {
        const body = request.postDataJSON()
        suppliers.push({ id: 'supplier-2', ...body })
        return route.fulfill({ status: 201, json: { id: 'supplier-2' } })
      }
      if (request.method() === 'PUT') {
        Object.assign(suppliers[0], request.postDataJSON())
        return route.fulfill({ status: 204 })
      }
      return route.fulfill({ json: { content: suppliers, page: 0, size: 20, totalElements: suppliers.length, totalPages: 1 } })
    })
    await page.goto('/suppliers')
    await expect(page.getByRole('heading', { name: 'Proveedores', exact: true })).toBeVisible()
    await expect(page.getByText('North supplier', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.getByRole('button', { name: '+ Nuevo proveedor', exact: true }).click()
    await expect(page.getByLabel('Nombre', { exact: true })).toBeFocused()
    await page.getByLabel('Nombre', { exact: true }).fill('New supplier')
    await page.getByRole('button', { name: 'Guardar proveedor' }).click()
    await expect(page.getByRole('status')).toHaveText('Proveedor creado correctamente.')
    await expect(page.getByText('New supplier', { exact: true })).toBeVisible()
    expect(suppliers[1]).toMatchObject({ phone: null, email: null, address: null })
    await page.getByRole('button', { name: 'Editar proveedor North supplier' }).click()
    await page.getByLabel('Mail (opcional)').fill('new@example.test')
    await page.getByRole('button', { name: 'Cancelar', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '¿Descartar los cambios?' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Seguir editando' })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(dialog.getByRole('button', { name: 'Descartar cambios' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(page.getByLabel('Mail (opcional)')).toHaveValue('new@example.test')
    await page.screenshot({ path: testInfo.outputPath(`supplier-form-${width}.png`), fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.getByRole('button', { name: 'Guardar cambios' }).click()
    await expect(page.getByRole('status')).toHaveText('Proveedor actualizado correctamente.')
    await expect(page.getByText('new@example.test', { exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath(`suppliers-${width}.png`), fullPage: true })
  })
}
