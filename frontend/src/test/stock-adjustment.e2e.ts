import { expect, test } from '@playwright/test'

const productName = 'Marca Demo 01 Producto Demo 001'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'STOCK_ADJUST'] }))}.signature`)
  })
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/products') return route.fulfill({ json: { content: [{ id: 'product-1', name: productName, stock: 116, category: 'Almacén', cost: 100, status: 'ACTIVE' }], page: 0, totalElements: 1, totalPages: 1 } })
    if (path === '/api/brands' || path === '/api/categories') return route.fulfill({ json: [] })
    return route.fulfill({ json: { content: [], page: 0, totalElements: 0, totalPages: 0 } })
  })
})

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }, { width: 320, height: 480 }]) {
  test(`adjustment layout and keyboard at ${viewport.width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport)
    await page.goto('/products')
    const trigger = page.getByRole('button', { name: `Ajustar stock de ${productName}` })
    await trigger.click()
    const dialog = page.getByRole('dialog', { name: 'Ajustar stock', exact: true })
    const quantity = dialog.getByLabel('Cantidad de ajuste')
    await expect(quantity).toBeFocused()
    await expect(dialog.getByRole('status', { name: 'Stock resultante' })).toHaveText('—')
    await page.keyboard.press('Shift+Tab')
    await expect(dialog.getByRole('button', { name: 'Confirmar ajuste' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(quantity).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await trigger.click()
    await quantity.fill('-0.5')
    await expect(dialog.getByRole('status', { name: 'Stock resultante' })).toHaveText('115,5')
    await dialog.getByLabel('Motivo', { exact: true }).fill('Corrección de conteo')
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
    const bounds = await dialog.boundingBox()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.width).toBeLessThanOrEqual(viewport.width)
    expect(bounds!.height).toBeLessThanOrEqual(viewport.height)
    await dialog.getByRole('button', { name: 'Confirmar ajuste' }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`adjustment-${viewport.width}.png`) })
    await page.keyboard.press('Escape')
    const discard = page.getByRole('dialog', { name: '¿Descartar los cambios?' })
    await discard.getByRole('button', { name: 'Seguir editando' }).click()
    await expect(quantity).toHaveValue('-0.5')
    await dialog.getByRole('button', { name: 'Cancelar', exact: true }).click()
    await discard.getByRole('button', { name: 'Descartar cambios' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(trigger).toBeFocused()
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  })
}

test('keeps the draft after failure, blocks pending closure and submits the adjustment', async ({ page }) => {
  let attempts = 0
  let completeRequest: (() => void) | undefined
  const pendingRequest = new Promise<void>((resolve) => { completeRequest = resolve })
  await page.route('**/api/inventory/product-1/adjustments', async (route) => {
    expect(route.request().postDataJSON()).toEqual({ quantity: -120, reason: 'Corrección de conteo' })
    attempts += 1
    if (attempts === 1) return route.fulfill({ status: 500, json: { detail: 'No se pudo registrar el ajuste.' } })
    await pendingRequest
    return route.fulfill({ status: 204 })
  })
  await page.goto('/products')
  await page.getByRole('button', { name: `Ajustar stock de ${productName}` }).click()
  const dialog = page.getByRole('dialog', { name: 'Ajustar stock', exact: true })
  await dialog.getByLabel('Cantidad de ajuste').fill('-0.25')
  await dialog.getByLabel('Motivo', { exact: true }).fill('Corrección de conteo')
  await dialog.getByRole('button', { name: 'Confirmar ajuste' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('La cantidad debe ser múltiplo de 0,5.')
  expect(attempts).toBe(0)
  await dialog.getByLabel('Cantidad de ajuste').fill('-120')
  await expect(dialog.getByText('Advertencia: el saldo quedará negativo (-4).')).toBeVisible()
  await dialog.getByRole('button', { name: 'Confirmar ajuste' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('No se pudo registrar el ajuste.')
  await expect(dialog.getByLabel('Cantidad de ajuste')).toHaveValue('-120')
  await dialog.getByRole('button', { name: 'Confirmar ajuste' }).click()
  await expect(dialog.getByRole('button', { name: 'Guardando...' })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: 'Cancelar', exact: true })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  completeRequest!()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveText('Ajuste de stock registrado.')
  expect(attempts).toBe(2)
})
