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
  await page.route('**/api/dashboard/seller-orders?*', (route) => route.fulfill({ json: {
    content: [{ id: 'order-1', number: 'PED-001', customer: 'Almacén Norte', date: '2026-09-01T03:00:00Z', deliveredAt: '2026-09-03T03:00:00Z', total: 100, paid: 40, accountBalance: 60, payments: [{ method: 'CASH', amount: 20 }, { method: 'BANK_TRANSFER', amount: 20 }] }],
    page: 0, size: 20, totalElements: 1, totalPages: 1,
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
    const view = page.getByRole('button', { name: 'Ver pedidos de Lucía Martínez' })
    await view.click()
    const detail = page.getByRole('dialog', { name: 'Pedidos entregados · Lucía Martínez' })
    await expect(detail.getByText('PED-001')).toBeVisible()
    await expect(detail.getByText(/Efectivo:.*20,00/)).toBeVisible()
    await expect(detail.getByText(/Transferencia:.*20,00/)).toBeVisible()
    const bounds = await detail.boundingBox()
    expect(bounds!.width).toBeLessThanOrEqual(width)
    expect(bounds!.height).toBeLessThanOrEqual(900)
    expect(await detail.evaluate((element) => element.scrollWidth)).toBeLessThanOrEqual(Math.ceil(bounds!.width))
    await page.screenshot({ path: testInfo.outputPath(`seller-orders-${width}.png`), fullPage: true })
    await page.keyboard.press('Escape')
    await expect(detail).toHaveCount(0)
    await expect(view).toBeFocused()
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
