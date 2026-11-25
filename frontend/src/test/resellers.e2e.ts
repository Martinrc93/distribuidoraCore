import { expect, test, type Page } from '@playwright/test'

const reseller = { id: 'r1', name: 'Distribuidora Sur con un nombre bastante largo', seller: 'Lucía', status: 'ACTIVE', balance: 1500, kind: 'RESELLER' }
const sellerProfile = { id: 's1', userId: 'u1', displayName: 'Lucía', email: 'lucia@example.test', status: 'ACTIVE', assignedCustomersCount: 1, kind: 'SELLER' }

async function setup(page: Page, dark: boolean) {
  await page.addInitScript(({ dark }) => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'RESELLER_MANAGE'] }))}.signature`)
    if (dark) localStorage.setItem('distribuidora.theme', 'dark')
  }, { dark })
  const state = { resellerId: null as string | null, resellers: [reseller], patches: [] as unknown[], errors: [] as string[] }
  page.on('pageerror', error => state.errors.push(error.message))
  const client = () => ({ id: 'c1', name: 'Kiosco Centro', seller: 'Sin asignar', status: 'ACTIVE', balance: 0, kind: 'REGULAR',
    resellerId: state.resellerId, reseller: state.resellerId ? reseller.name : null })
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname
    if (path === '/api/customers/c1/reseller-assignment') return route.fulfill({ json: { id: 'c1', name: 'Kiosco Centro', kind: 'REGULAR', resellerId: state.resellerId, associatedCustomersCount: 0,
      pendingOrdersCount: 2, pendingOrders: [{ id: 'o1', number: '000400007', total: 12500, account: 'Kiosco Centro' }, { id: 'o2', number: '000400008', total: 3200, account: 'Kiosco Centro' }] } })
    if (path === '/api/customers/c1/reseller' && request.method() === 'PATCH') {
      const body = request.postDataJSON(); state.patches.push(body); state.resellerId = body.resellerId
      return route.fulfill({ json: { customerId: 'c1', resellerId: body.resellerId, pendingOrdersKept: 2 } })
    }
    if (path === '/api/customers') {
      const search = url.searchParams.get('search')?.toLowerCase() ?? ''
      const profiles = url.searchParams.get('kind') === 'RESELLER' ? state.resellers : [...state.resellers, client()]
      const content = profiles.filter((profile) => profile.name.toLowerCase().includes(search))
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1 } })
    }
    if (path === '/api/sellers/profiles') {
      const search = url.searchParams.get('search')?.toLowerCase() ?? ''
      const kind = url.searchParams.get('kind')
      const profiles = [sellerProfile, ...state.resellers.map((item) => ({ id: item.id, displayName: item.name,
        email: null, status: item.status, kind: item.kind, assignedCustomersCount: 0 }))]
      const content = profiles.filter((profile) => (!kind || profile.kind === kind) && profile.displayName.toLowerCase().includes(search))
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1 } })
    }
    if (path === '/api/customers/filter-options') return route.fulfill({ json: { sellers: [] } })
    if (path === '/api/zones') return route.fulfill({ json: [] })
    return route.fulfill({ json: { content: [], totalPages: 0 } })
  })
  return state
}

async function fitsViewport(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(await page.evaluate(() => innerWidth))
}

for (const width of [320, 640, 760, 1050, 1280]) {
  test(`assigns a reseller without overflowing at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page, width === 760 || width === 320)
    await page.goto('/customers')
    await expect(page.getByRole('combobox', { name: 'Buscar por revendedor' })).toBeVisible()
    await fitsViewport(page)

    await page.getByLabel('Más acciones de Kiosco Centro').click()
    await page.getByRole('button', { name: 'Revendedor de Kiosco Centro' }).click()
    const dialog = page.getByRole('dialog', { name: 'Revendedor de Kiosco Centro' })
    await expect(dialog.getByText(/Solo cambian los pedidos nuevos/)).toBeVisible()
    await dialog.getByRole('combobox', { name: 'Revendedor' }).click()
    await page.getByRole('option', { name: reseller.name }).click()
    await expect(dialog.getByText(/Hay 2 pedidos sin entregar que seguirán en la cuenta actual/)).toBeVisible()
    await expect(dialog.getByText(/Pedido 000400008/)).toBeVisible()
    const bounds = await dialog.boundingBox()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width)
    await fitsViewport(page)
    await page.screenshot({ path: info.outputPath(`reseller-dialog-${width}.png`) })

    await dialog.getByRole('button', { name: 'Guardar cambios' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Revendedor asignado' })).toBeVisible()
    await expect(dialog).toBeHidden()
    await fitsViewport(page)
    await page.screenshot({ path: info.outputPath(`reseller-list-${width}.png`), fullPage: true })
    await page.getByLabel('Más acciones de Kiosco Centro').click()
    await page.getByRole('button', { name: 'Revendedor de Kiosco Centro' }).click()
    await expect(dialog.getByRole('combobox', { name: 'Revendedor' })).toHaveValue(reseller.name)
    expect(state.patches).toEqual([{ resellerId: 'r1' }])
    expect(state.errors).toEqual([])
  })
}

test('closing the reseller dialog with Escape asks before discarding a change', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await setup(page, false)
  await page.goto('/customers')
  await page.getByLabel('Más acciones de Kiosco Centro').click()
  await page.getByRole('button', { name: 'Revendedor de Kiosco Centro' }).click()
  const dialog = page.getByRole('dialog', { name: 'Revendedor de Kiosco Centro' })
  await dialog.getByRole('combobox', { name: 'Revendedor' }).click()
  await page.getByRole('option', { name: reseller.name }).click()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog').last()).toContainText(/descartar|Descartar/)
  expect(state.patches).toEqual([])
  expect(state.errors).toEqual([])
})

test('creates a reseller from New Seller even when user accounts cannot load', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 })
  const state = await setup(page, false)
  await page.route('**/api/users?*', (route) => route.fulfill({ status: 503, json: {} }))
  await page.route('**/api/customers', (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    const { businessName } = route.request().postDataJSON()
    state.resellers.push({ ...reseller, id: 'new-reseller', name: businessName, balance: 0 })
    return route.fulfill({ status: 201, json: { id: 'new-reseller' } })
  })
  await page.goto('/admin/sellers')
  const table = page.getByRole('table')
  await expect(table.getByText(sellerProfile.displayName, { exact: true })).toBeVisible()
  await expect(table.getByText(reseller.name, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /nuevo vendedor/i }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Nuevo vendedor' })
  const type = dialog.getByLabel('Tipo de perfil')
  await expect(dialog.getByLabel('Usuario vinculado')).toBeDisabled()
  await expect(dialog.getByRole('button', { name: 'Guardar vendedor' })).toBeDisabled()
  await type.selectOption('RESELLER')
  await expect(dialog.getByLabel('Usuario vinculado')).toHaveCount(0)
  await dialog.getByLabel('Nombre del revendedor').fill('Distribuidora Norte')
  await fitsViewport(page)

  const request = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/customers')
  await dialog.getByRole('button', { name: 'Guardar revendedor' }).click()
  expect((await request).postDataJSON()).toEqual({ businessName: 'Distribuidora Norte', kind: 'RESELLER' })
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('status').filter({ hasText: 'Revendedor creado correctamente' })).toBeVisible()
  await expect(page.getByLabel('Mostrar')).toHaveValue('')
  const search = page.getByRole('textbox', { name: 'Buscar perfiles' })
  await expect(search).toHaveValue('Distribuidora Norte')
  const profile = page.getByRole('link', { name: 'Ver ficha de Distribuidora Norte' })
  await expect(profile).toBeVisible()
  await expect(profile).toHaveAttribute('href', /\/customers\/new-reseller$/)
  await search.clear()
  await expect(table.getByText(reseller.name, { exact: true })).toBeVisible()
  await expect(table.getByText(sellerProfile.displayName, { exact: true })).toBeVisible()
  await expect(table.getByText('Vendedor', { exact: true })).toBeVisible()
  await expect(table.getByText('Revendedor', { exact: true })).toHaveCount(2)
  await page.getByLabel('Mostrar').selectOption('resellers')
  await expect(table.getByText(sellerProfile.displayName, { exact: true })).toHaveCount(0)
  await expect(profile).toBeVisible()
  await fitsViewport(page)
  expect(state.errors).toEqual([])
})
