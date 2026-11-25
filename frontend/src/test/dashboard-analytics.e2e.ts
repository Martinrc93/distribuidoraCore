import { expect, test } from '@playwright/test'
import { analyticsReport } from './fixtures/analyticsDashboard'

test.beforeEach(async ({ page }) => {
  page.on('pageerror', (error) => console.error(error.message))
  page.on('console', (message) => { if (message.type() === 'error') console.error(message.text()) })
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('distribuidora.accessToken')) sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
  })
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/dashboard/analytics') return route.fulfill({ json: analyticsReport })
    return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
  })
})
for (const width of [320, 640, 760, 1050, 1440]) {
  test(`dashboard works without document overflow at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/analytics?dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026')
    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible()
    await expect(page.getByText('Ventas del período', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await expect(page.getByRole('img', { name: /Ventas diarias/ })).toBeVisible()
    const debtors = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Clientes con mayor saldo por cobrar' }) })
    await expect(debtors.getByRole('status')).toContainText('100% del total por cobrar')
    await expect(debtors.locator('td[data-label="% del total"]')).toHaveText('100%')
    await expect(debtors.getByRole('link', { name: 'Ver deuda de Cliente principal' })).toHaveAttribute('href', '/sales?accountCustomerId=customer-1&pendingBalance=true')
    await page.getByText('Ver datos del gráfico', { exact: true }).click()
    if (width > 640) await expect(page.getByRole('columnheader', { name: 'Importe', exact: true }).first()).toBeVisible()
    else await expect(page.locator('details[open]')).toContainText('01/09/2026')
    expect(await page.locator('table').evaluateAll((tables) => tables.filter((table) => table.parentElement?.className.includes('min-w-0')).every((table) => table.scrollWidth <= table.parentElement!.clientWidth))).toBe(true)
    if (width <= 1050) await page.getByRole('button', { name: 'Abrir menú' }).click()
    const links = page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link')
    expect((await links.allTextContents()).slice(0, 7)).toEqual(['Resumen', 'Dashboard', 'Pedidos', 'Ventas', 'Compras', 'Clientes', 'Gastos'])
    await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toHaveAttribute('aria-current', 'page')
    if (width <= 1050) await page.keyboard.press('Escape')
    await page.screenshot({ path: info.outputPath(`dashboard-${width}.png`), fullPage: true })
  })
}
test('period controls, calendar keyboard and administrative access work', async ({ page }) => {
  await page.goto('/analytics')
  await expect(page.getByText('Ventas del período', { exact: true })).toBeVisible()
  await page.getByLabel('Período', { exact: true }).selectOption('today')
  await expect(page).toHaveURL(/dateMin=/)
  await page.getByRole('button', { name: 'Abrir calendario: Desde' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Abrir calendario: Desde' })).toBeFocused()
  await page.evaluate(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['SELLER'] }))}.signature`))
  const requests: string[] = []
  page.on('request', (request) => requests.push(request.url()))
  await page.goto('/analytics')
  await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toHaveCount(0)
  expect(requests.some((url) => url.includes('/api/dashboard/analytics'))).toBe(false)
})

test('registers an expense on mobile and shows it in the refreshed dashboard', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 })
  const entries: typeof analyticsReport.expenses.recent = []
  const submittedIds: string[] = []
  await page.route('**/api/expenses**', async (route) => {
    if (route.request().method() === 'POST') {
      const { idempotencyKey, type, amount, observation } = route.request().postDataJSON()
      submittedIds.push(idempotencyKey)
      if (submittedIds.length === 1) return route.fulfill({ status: 500, json: { detail: 'No se pudo guardar.' } })
      const entry = { id: idempotencyKey, type, amount, observation, createdAt: '2026-09-05T15:00:00Z' }
      entries.push(entry)
      return route.fulfill({ status: 201, json: entry })
    }
    return route.fulfill({ json: { content: entries, page: 0, size: 20, totalElements: entries.length, totalPages: entries.length } })
  })
  await page.route('**/api/dashboard/analytics?**', (route) => route.fulfill({
    json: { ...analyticsReport, expenses: { amount: entries[0]?.amount ?? 0, count: entries.length, recent: entries } },
  }))

  await page.goto('/analytics?dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026')
  await expect(page.getByText('No hay gastos registrados en este período.')).toBeVisible()
  // Use the app navigation so the dashboard's query stays in the cache.
  await page.getByRole('button', { name: 'Abrir menú' }).click()
  await page.getByRole('link', { name: 'Gastos', exact: true }).click()
  await expect(page.getByText('No hay gastos registrados hoy.', { exact: true })).toBeVisible()
  const create = page.getByRole('button', { name: 'Nuevo gasto' })
  await create.click()
  const dialog = page.getByRole('dialog', { name: 'Nuevo gasto' })
  const amount = dialog.getByLabel('Importe ($)')
  await expect(dialog.getByRole('combobox', { name: 'Tipo', exact: true }).locator('option')).toHaveText([
    'Seleccioná un tipo', 'Caja chica', 'Combustible', 'Servicios', 'Otros',
  ])
  await expect(amount).toBeFocused()
  await amount.fill('0')
  await dialog.getByLabel('Tipo').selectOption({ label: 'Combustible' })
  await dialog.getByRole('button', { name: 'Guardar gasto' }).click()
  await expect(dialog.getByRole('alert')).toContainText('mayor a cero')
  await amount.fill('120,75')
  await dialog.getByLabel('Observación (opcional)').fill('Recarga para entregas')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Seguir editando' }).click()
  await expect(amount).toHaveValue('120,75')
  await dialog.getByRole('button', { name: 'Guardar gasto' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('No se pudo guardar.')
  await dialog.getByRole('button', { name: 'Guardar gasto' }).click()
  await expect(page.getByRole('status', { name: '' }).filter({ hasText: 'Gasto registrado.' })).toBeVisible()
  await expect(create).toBeFocused()
  await expect(page.getByText('Recarga para entregas')).toBeVisible()
  expect(submittedIds).toHaveLength(2)
  expect(submittedIds[0]).toBe(submittedIds[1])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)

  await page.goBack()
  const panel = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Gastos del período' }) })
  await expect(panel).toContainText('$ 120,75 en 1 gasto registrado.')
  await expect(panel.getByText('Combustible', { exact: true })).toBeVisible()
  await expect(panel.getByText('Recarga para entregas')).toBeVisible()
})

test('shows today by default, paginates 50 expenses, and opens the expense detail', async ({ page }, info) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await page.clock.setFixedTime(new Date('2026-11-20T01:00:00Z'))
  const requests: URLSearchParams[] = []
  const entries = Array.from({ length: 51 }, (_, index) => ({
    id: `expense-${index + 1}`, amount: 180, type: 'FUEL',
    observation: `Carga de combustible ${index + 1}\nPara entregas.`, createdAt: '2026-11-19T15:00:00Z',
  }))
  await page.route('**/api/expenses?**', (route) => {
    const params = new URL(route.request().url()).searchParams
    requests.push(params)
    const pageNumber = Number(params.get('page'))
    const size = Number(params.get('size'))
    const eligible = (params.get('dateMax') ?? '2026-11-19') < '2026-11-19' ? [] : entries
    const content = eligible.slice(pageNumber * size, (pageNumber + 1) * size)
    return route.fulfill({ json: { content, page: pageNumber, size, totalElements: eligible.length, totalPages: Math.ceil(eligible.length / size) } })
  })

  await page.goto('/expenses')
  const from = page.getByLabel('Desde', { exact: true })
  const to = page.getByLabel('Hasta', { exact: true })
  await expect(from).toHaveValue('19/11/2026')
  await expect(to).toHaveValue('19/11/2026')
  await expect(page.getByText('Página 1 · 51 gastos')).toBeVisible()
  expect(Object.fromEntries(requests.at(-1)!)).toEqual({ page: '0', size: '50', dateMin: '2026-11-19', dateMax: '2026-11-19' })
  const rows = page.locator('.data-table tbody tr')
  await expect(rows).toHaveCount(50)
  await page.getByRole('button', { name: 'Siguiente' }).click()
  await expect(page.getByText('Página 2 · 51 gastos')).toBeVisible()
  await expect(rows).toHaveCount(1)
  const detailAction = page.getByRole('button', { name: /Ver detalle de Combustible/ })
  const dialog = page.getByRole('dialog', { name: 'Detalle del gasto' })
  await detailAction.click()
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Cerrar' })).toBeFocused()
  await expect(dialog).toContainText('$ 180,00')
  await expect(dialog.locator('dd').last()).toHaveJSProperty('textContent', entries[50].observation)
  await page.screenshot({ path: info.outputPath('expense-detail-mobile.png') })
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(detailAction).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Cerrar' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(detailAction).toBeFocused()

  const type = page.getByLabel('Tipo de gasto')
  await expect(type.locator('option')).toHaveText(['Todos los tipos', 'Caja chica', 'Combustible', 'Servicios', 'Otros'])
  await type.selectOption('FUEL')
  await expect(page.getByText('Página 1 · 51 gastos')).toBeVisible()
  await from.fill('01/09/2026')
  await to.fill('31/08/2026')
  await expect(page.getByRole('alert')).toHaveText('La fecha inicial no puede ser posterior a la final.')
  expect(requests.some((params) => params.get('dateMax') === '2026-08-31')).toBe(false)
  await to.fill('30/09/2026')
  await expect(page.getByText('No hay gastos para los filtros seleccionados.')).toBeVisible()
  expect(Object.fromEntries(requests.at(-1)!)).toEqual({ page: '0', size: '50', type: 'FUEL', dateMin: '2026-09-01', dateMax: '2026-09-30' })
  await page.reload()
  await expect(type).toHaveValue('FUEL')
  await expect(from).toHaveValue('01/09/2026')
  await expect(to).toHaveValue('30/09/2026')
  await page.getByRole('button', { name: 'Limpiar filtros' }).click()
  await expect(page).toHaveURL(/\/expenses\?dateMin=&dateMax=$/)
  await expect(from).toHaveValue('')
  await expect(to).toHaveValue('')
  await expect(page.getByText('Página 1 · 51 gastos')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
})
