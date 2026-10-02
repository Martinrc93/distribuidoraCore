import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
  })
  await page.route('**/api/dashboard?*', (route) => route.fulfill({ json: {
    dateMin: new URL(route.request().url()).searchParams.get('dateMin'),
    dateMax: new URL(route.request().url()).searchParams.get('dateMax'),
    totals: { performedOrders: 42, deliveredOrders: 31, totalBilled: 1234567.89, totalPaid: 950000.50, accountBalance: 284567.39 },
    bySeller: [
      { sellerId: '1', seller: 'Lucía Martínez', performedOrders: 30, deliveredOrders: 25, totalBilled: 1000000.89, totalPaid: 750000.50, accountBalance: 250000.39 },
      { sellerId: null, seller: 'Sin asignar', performedOrders: 12, deliveredOrders: 6, totalBilled: 234567, totalPaid: 200000, accountBalance: 34567 },
    ],
  } }))
})

for (const width of [320, 390, 640, 760, 1050, 1440]) {
  test(`summary and calendar work at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/dashboard?dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026')
    await expect(page.getByText('Lucía Martínez')).toBeVisible()
    await expect(page.getByRole('region', { name: 'Totales del período' })).toContainText('1.234.567,89')
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: testInfo.outputPath(`summary-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: 'Abrir calendario: Desde' }).click()
    const calendar = page.getByRole('dialog', { name: 'Calendario: Desde' })
    await expect(calendar).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(calendar).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Abrir calendario: Desde' })).toBeFocused()
    await page.getByLabel('Desde', { exact: true }).fill('01/10/2026')
    await page.keyboard.press('Tab')
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('region', { name: 'Totales del período' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Hoy', exact: true }).click()
    await expect(page.getByText('Lucía Martínez')).toBeVisible()
  })
}
