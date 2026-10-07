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
    const debtors = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Clientes con mayor deuda' }) })
    await expect(debtors.getByRole('status')).toContainText('100% de la deuda total')
    await expect(debtors.locator('td[data-label="% del total"]')).toHaveText('100%')
    await expect(debtors.getByRole('link', { name: 'Ver deuda de Cliente principal' })).toHaveAttribute('href', '/sales?customerId=customer-1&pendingBalance=true')
    await page.getByText('Ver datos del gráfico', { exact: true }).click()
    if (width > 640) await expect(page.getByRole('columnheader', { name: 'Importe', exact: true }).first()).toBeVisible()
    else await expect(page.locator('details[open]')).toContainText('01/09/2026')
    expect(await page.locator('table').evaluateAll((tables) => tables.filter((table) => table.parentElement?.className.includes('min-w-0')).every((table) => table.scrollWidth <= table.parentElement!.clientWidth))).toBe(true)
    if (width <= 1050) await page.getByRole('button', { name: 'Abrir menú' }).click()
    const links = page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link')
    expect((await links.allTextContents()).slice(0, 2)).toEqual(['Resumen', 'Dashboard'])
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
