import { expect, test } from '@playwright/test'

const productName = 'Marca Demo 01 Producto Demo 001'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'STOCK_ADJUST', 'STOCK_SHRINKAGE'] }))}.signature`)
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
    const quantity = dialog.getByRole('textbox', { name: 'Cantidad final' })
    await expect(quantity).toBeFocused()
    await expect(dialog.getByRole('status', { name: 'Stock resultante' })).toHaveText('—')
    await page.keyboard.press('Shift+Tab')
    await expect(dialog.getByRole('radio', { name: 'Indicar cantidad final' })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(dialog.getByRole('button', { name: 'Confirmar ajuste' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(dialog.getByRole('radio', { name: 'Indicar cantidad final' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await trigger.click()
    await quantity.fill('112')
    await expect(dialog.getByRole('status', { name: 'Stock resultante' })).toHaveText('112')
    await expect(dialog.getByText('Diferencia: -4.')).toBeVisible()
    await dialog.getByLabel('Motivo (opcional)').fill('Corrección de conteo')
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
    await expect(quantity).toHaveValue('112')
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
  await dialog.getByRole('radio', { name: 'Sumar o restar' }).check()
  await dialog.getByLabel('Cantidad de ajuste').fill('-0.25')
  await dialog.getByLabel('Motivo (opcional)').fill('Corrección de conteo')
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

for (const width of [320, 390, 1440]) {
  test(`records a shrinkage with safe retries and reports it by period at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: width === 320 ? 640 : 900 })
    const name = width === 320 ? `Marca Demo ${'Producto'.repeat(14)}` : productName
    let stock = 2
    let attempts = 0
    let movementId = ''
    const queriedPeriods: string[] = []
    await page.route((url) => url.pathname === '/api/products', (route) => {
      const positive = new URL(route.request().url()).searchParams.get('stock') === 'POSITIVE'
      const content = positive && stock <= 0 ? [] : [{ id: 'product-1', name, stock, category: 'Almacén', cost: 100, status: 'ACTIVE' }]
      return route.fulfill({ json: { content, page: 0, totalElements: content.length, totalPages: content.length } })
    })
    await page.route((url) => url.pathname === '/api/inventory/product-1/shrinkages', (route) => {
      const body = route.request().postDataJSON()
      expect(body).toEqual({ idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/), quantity: 2.5, reason: 'Envases dañados' })
      attempts++
      if (attempts === 1) {
        movementId = body.idempotencyKey
        stock -= body.quantity
        return route.fulfill({ status: 503, json: { detail: 'No llegó la confirmación.' } })
      }
      expect(body.idempotencyKey).toBe(movementId)
      return route.fulfill({ status: 204 })
    })
    await page.route((url) => url.pathname === '/api/inventory/shrinkages', (route) => {
      const params = new URL(route.request().url()).searchParams
      queriedPeriods.push(`${params.get('dateMin') ?? ''}|${params.get('dateMax') ?? ''}`)
      return route.fulfill({ json: {
        totalQuantity: 2.5,
        movements: { content: [{ id: movementId, product: name, date: '2026-11-17T13:00:00Z', quantity: 2.5, reason: 'Envases dañados' }],
          page: 0, size: 20, totalElements: 1, totalPages: 1 },
      } })
    })
    await page.goto('/products?stock=POSITIVE')
    await page.getByLabel('Más acciones de ' + name).press('Enter')
    await page.getByRole('button', { name: `Registrar merma de ${name}` }).click()
    const dialog = page.getByRole('dialog', { name: 'Registrar merma', exact: true })
    const quantity = dialog.getByLabel('Cantidad perdida')
    const reason = dialog.getByLabel('Motivo (obligatorio)')
    const confirm = dialog.getByRole('button', { name: 'Confirmar merma' })
    await expect(quantity).toBeFocused()
    await quantity.fill('0,25')
    await reason.fill('   ')
    await confirm.click()
    await expect(dialog.getByRole('alert')).toContainText('múltiplos de 0,5')
    await quantity.fill('2,5')
    await confirm.click()
    await expect(dialog.getByRole('alert')).toContainText('Ingresá el motivo')
    expect(attempts).toBe(0)
    await reason.fill(' Envases dañados ')
    await expect(dialog.getByText('El saldo estimado quedará negativo (-0,5).')).toBeVisible()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Seguir editando' }).click()
    await expect(reason).toHaveValue(' Envases dañados ')
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`merma-${width}.png`) })
    await confirm.click()
    await expect(dialog.getByRole('alert')).toContainText('No llegó la confirmación.')
    await confirm.click()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('status')).toHaveText('Merma registrada. El stock fue actualizado.')
    await expect(page.getByRole('textbox', { name: 'Buscar productos' })).toBeFocused()
    expect(attempts).toBe(2)

    await page.getByRole('link', { name: 'Ver mermas' }).click()
    await expect(page.getByRole('heading', { name: 'Mermas', exact: true })).toBeVisible()
    await page.getByLabel('Desde', { exact: true }).fill('17/11/2026')
    await page.getByLabel('Hasta', { exact: true }).fill('16/11/2026')
    await expect(page.getByRole('alert')).toContainText('La fecha inicial no puede ser posterior a la final.')
    await expect(page.getByText('Envases dañados', { exact: true })).toHaveCount(0)
    await page.getByLabel('Hasta', { exact: true }).fill('17/11/2026')
    await expect(page.getByText('Envases dañados', { exact: true })).toBeVisible()
    await expect(page.getByText(/Cantidad perdida en el período/)).toContainText('2,5')
    expect(queriedPeriods).toContain('2026-11-17|2026-11-17')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`reporte-mermas-${width}.png`) })
    await expect(page.getByRole('link', { name: 'Volver a Stock' })).toHaveAttribute('href', '/products?stock=POSITIVE')
  })
}
