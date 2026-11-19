import { expect, test, type Page } from '@playwright/test'

const reseller = { id: 'r1', name: 'Distribuidora Sur con un nombre bastante largo', seller: 'Lucía', status: 'ACTIVE', balance: 1500, kind: 'RESELLER' }

async function setup(page: Page, dark: boolean) {
  await page.addInitScript(({ dark }) => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL', 'RESELLER_MANAGE'] }))}.signature`)
    if (dark) localStorage.setItem('distribuidora.theme', 'dark')
  }, { dark })
  const state = { resellerId: null as string | null, patches: [] as unknown[], errors: [] as string[] }
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
      const content = url.searchParams.get('kind') === 'RESELLER' ? [reseller] : [reseller, client()]
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
    await expect(page.getByText('Revendedor', { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('combobox', { name: 'Buscar por revendedor' })).toBeVisible()
    await fitsViewport(page)

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
    await expect(page.getByText(`Cuenta: ${reseller.name}`)).toBeVisible()
    await expect(dialog).toBeHidden()
    await fitsViewport(page)
    await page.screenshot({ path: info.outputPath(`reseller-list-${width}.png`), fullPage: true })
    expect(state.patches).toEqual([{ resellerId: 'r1' }])
    expect(state.errors).toEqual([])
  })
}

test('closing the reseller dialog with Escape asks before discarding a change', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await setup(page, false)
  await page.goto('/customers')
  await page.getByRole('button', { name: 'Revendedor de Kiosco Centro' }).click()
  const dialog = page.getByRole('dialog', { name: 'Revendedor de Kiosco Centro' })
  await dialog.getByRole('combobox', { name: 'Revendedor' }).click()
  await page.getByRole('option', { name: reseller.name }).click()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog').last()).toContainText(/descartar|Descartar/)
  expect(state.patches).toEqual([])
  expect(state.errors).toEqual([])
})
