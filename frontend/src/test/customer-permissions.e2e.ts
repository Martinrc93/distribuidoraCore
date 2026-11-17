import { expect, test, type Page } from '@playwright/test'

async function setup(page: Page, permission: string, dark: boolean, failAssignment = false) {
  await page.addInitScript(({ permission, dark }) => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: [permission] }))}.signature`)
    if (dark) localStorage.setItem('distribuidora.theme', 'dark')
  }, { permission, dark })
  const state = { created: undefined as Record<string, unknown> | undefined, list: undefined as Record<string, unknown> | undefined, payment: undefined as Record<string, unknown> | undefined, balance: 30, status: 'ACTIVE', assignmentAttempts: 0, paths: [] as string[], errors: [] as string[] }
  page.on('pageerror', error => state.errors.push(error.message))
  const customer = () => ({ id: 'c1', name: 'Cliente asignado con un nombre largo', seller: 'Lucía', sellerId: 'self', priceListId: state.list ? 'other' : 'general', status: state.status, balance: state.balance })
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname; state.paths.push(path)
    const request = route.request(); const method = request.method()
    if (path === '/api/customers/assignment-context') {
      if (failAssignment && ++state.assignmentAttempts === 1) return route.fulfill({ status: 503, json: { detail: 'Configuración no disponible' } })
      return route.fulfill({ json: { id: 'self', displayName: 'Lucía', email: 'self@example.test' } })
    }
    if (path === '/api/customers/c1/account-balance') return route.fulfill({ json: customer() })
    if (path.endsWith('/account-payments/settlement')) {
      state.payment = request.postDataJSON()
      state.balance -= (state.payment!.payments as Array<{ amount: number }>).reduce((sum, payment) => sum + payment.amount, 0)
      return route.fulfill({ status: 201, json: { balanceAfter: state.balance } })
    }
    if (path.endsWith('/price-list')) { state.list = request.postDataJSON(); return route.fulfill({ status: 204 }) }
    if (path.endsWith('/status')) { state.status = request.postDataJSON().status; return route.fulfill({ status: 204 }) }
    if (path === '/api/customers' && method === 'POST') { state.created = request.postDataJSON(); return route.fulfill({ status: 201, json: { id: 'new' } }) }
    if (path === '/api/customers') {
      const content = new URL(request.url()).searchParams.get('status') === 'ACTIVE' && state.status !== 'ACTIVE' ? [] : [customer()]
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: content.length ? 1 : 0 } })
    }
    if (path === '/api/zones') return route.fulfill({ json: [{ id: 'z1', name: 'Centro' }] })
    if (path === '/api/pricing/lists') return route.fulfill({ json: { content: [{ id: 'general', code: 'GENERAL', name: 'General', status: 'ACTIVE' }, { id: 'other', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }], totalElements: 2, totalPages: 1 } })
    return route.fulfill({ json: { content: [], totalPages: 0 } })
  })
  return state
}

test('status confirmation restores focus when the customer leaves the active filter', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await setup(page, 'CUSTOMER_STATUS_CHANGE', true)
  await page.goto('/customers')
  await page.getByRole('button', { name: 'Desactivar cliente', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /desactivar a Cliente/ })
  await expect(dialog.getByRole('button', { name: 'Volver' })).toBeFocused()
  await dialog.getByRole('button', { name: 'Confirmar', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Cliente desactivado')
  await expect(page.getByRole('textbox', { name: 'Buscar clientes' })).toBeFocused()
  expect(state.status).toBe('INACTIVE')
  expect(state.errors).toEqual([])
})
async function fits(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(await page.evaluate(() => innerWidth))
  const dialog = page.getByRole('dialog').last(); const bounds = await dialog.boundingBox()
  expect(bounds!.width).toBeLessThanOrEqual(await page.evaluate(() => innerWidth))
  expect(bounds!.height).toBeLessThanOrEqual(await page.evaluate(() => innerHeight))
}

for (const width of [320, 390, 1280]) {
  test(`own customer creation, metadata retry and discard at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 }); const state = await setup(page, 'CUSTOMER_CREATE', width === 390, width === 320)
    await page.goto('/customers'); const trigger = page.getByRole('button', { name: '+ Nuevo cliente' }); await trigger.click()
    const dialog = page.getByRole('dialog', { name: 'Nuevo cliente', exact: true })
    await expect(dialog.getByLabel('Razón social')).toBeFocused()
    await dialog.getByLabel('Razón social').fill('Nuevo cliente asignado')
    if (width === 320) {
      await expect(dialog.getByRole('button', { name: 'Guardar cliente' })).toBeDisabled()
      await dialog.getByRole('button', { name: 'Reintentar asignación' }).click()
    }
    await expect(dialog.getByRole('button', { name: 'Guardar cliente' })).toBeEnabled()
    await expect(dialog.getByRole('combobox', { name: 'Vendedor asignado' })).toBeDisabled()
    await expect(dialog.getByLabel('Lista de precios predeterminada')).toBeDisabled()
    await page.keyboard.press('Escape'); await page.getByRole('button', { name: 'Seguir editando' }).click()
    await expect(dialog.getByLabel('Razón social')).toHaveValue('Nuevo cliente asignado')
    await fits(page); await page.screenshot({ path: info.outputPath(`create-${width}.png`), fullPage: true })
    await dialog.getByRole('button', { name: 'Guardar cliente' }).click()
    await expect(page.getByRole('status')).toContainText('Cliente creado correctamente')
    await expect(trigger).toBeFocused()
    expect(state.created).toMatchObject({ businessName: 'Nuevo cliente asignado', sellerId: 'self' })
    expect(state.paths.some(path => path.startsWith('/api/sellers'))).toBe(false)
    expect(state.errors).toEqual([])
  })

  test(`assigned combined payment without customer detail access at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 }); const state = await setup(page, 'ACCOUNT_SETTLE', width === 390)
    await page.goto('/customers'); const trigger = page.getByRole('button', { name: /Registrar pago de/ }); await trigger.click()
    const dialog = page.getByRole('dialog', { name: 'Pago de cuenta corriente' })
    await expect(dialog.getByLabel('Importe del pago')).toHaveValue('30,00')
    await dialog.getByLabel('Importe del pago').click()
    await dialog.getByLabel('Importe del pago').fill('10')
    await expect(dialog.getByLabel('Importe del pago')).toHaveValue('10')
    await dialog.getByRole('button', { name: 'Agregar pago' }).click()
    await dialog.getByLabel('Medio de pago').nth(1).selectOption('BANK_TRANSFER')
    await dialog.getByLabel('Importe del pago').nth(1).click()
    await dialog.getByLabel('Importe del pago').nth(1).fill('5')
    await dialog.getByLabel('Número de transferencia').fill('TEST-TRANSFER')
    await fits(page); await page.screenshot({ path: info.outputPath(`payment-${width}.png`), fullPage: true })
    await dialog.getByRole('button', { name: 'Confirmar pago' }).click()
    await expect(page.getByRole('status')).toContainText('Saldo pendiente')
    expect(state.balance).toBe(15)
    expect(state.payment).toEqual({ payments: [{ method: 'CASH', amount: 10 }, { method: 'BANK_TRANSFER', amount: 5, transferReference: 'TEST-TRANSFER' }], partial: true })
    expect(state.paths).not.toContain('/api/customers/c1')
    expect(state.errors).toEqual([])
  })

  test(`independent default price list selection at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 }); const state = await setup(page, 'CUSTOMER_PRICE_LIST_CHANGE', width === 390)
    await page.goto('/customers'); await page.getByRole('button', { name: 'Cambiar lista', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: /Lista de precios de Cliente/ })
    await dialog.getByLabel('Lista de precios predeterminada').selectOption('other')
    await fits(page); await page.screenshot({ path: info.outputPath(`list-${width}.png`), fullPage: true })
    await dialog.getByRole('button', { name: 'Guardar lista' }).click()
    await expect(page.getByRole('status')).toContainText('Lista de precios actualizada')
    expect(state.list).toEqual({ priceListId: 'other' })
    expect(state.paths).not.toContain('/api/zones')
    expect(state.paths).not.toContain('/api/customers/assignment-context')
    expect(state.errors).toEqual([])
  })
}
