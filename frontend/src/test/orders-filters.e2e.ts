import { expect, test } from '@playwright/test'

test('order date filters default to today, persist across pages and adapt to mobile', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date('2026-09-30T12:00:00Z'))
  await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken', 'test-access-token'))
  const requests: URL[] = []
  await page.route('**/api/orders?*', async (route) => {
    const url = new URL(route.request().url())
    requests.push(url)
    const currentPage = Number(url.searchParams.get('page'))
    await route.fulfill({ json: {
      content: [{ id: `order-${currentPage}`, number: `PED-${currentPage}`, customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-30T12:00:00Z' }],
      page: currentPage, size: 20, totalElements: 21, totalPages: 2,
    } })
  })
  await page.goto('/orders')
  const min = page.getByLabel('Fecha mín.')
  const max = page.getByLabel('Fecha máx.')
  await expect(min).toHaveValue('30/09/2026')
  await expect(max).toHaveValue('30/09/2026')
  await expect(page.getByText('PED-0', { exact: true })).toBeVisible()
  expect(requests.at(-1)?.searchParams.get('dateMin')).toBe('2026-09-30')
  expect(requests.at(-1)?.searchParams.get('dateMax')).toBe('2026-09-30')

  await min.focus()
  await expect(min).toBeFocused()
  expect(await min.evaluate((input) => Number.parseFloat(getComputedStyle(input).outlineWidth))).toBeGreaterThan(0)
  await page.keyboard.press('Tab')
  await expect(max).toBeFocused()

  const statusBox = await page.getByLabel('Filtrar por estado').boundingBox()
  const minBox = await min.boundingBox()
  if (testInfo.project.name.startsWith('desktop')) expect(minBox!.x).toBeGreaterThan(statusBox!.x + statusBox!.width)
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
  await page.screenshot({ path: testInfo.outputPath('orders-date-filters.png'), fullPage: true })

  await min.fill('01/09/2026')
  await expect.poll(() => requests.at(-1)?.searchParams.get('dateMin')).toBe('2026-09-01')
  await page.getByRole('button', { name: 'Siguiente' }).click()
  await expect(page.getByText('PED-1', { exact: true })).toBeVisible()
  expect(requests.at(-1)?.searchParams.get('dateMin')).toBe('2026-09-01')
  await max.fill('25/09/2026')
  await expect(page.getByText('PED-0', { exact: true })).toBeVisible()
  expect(requests.at(-1)?.searchParams.get('dateMax')).toBe('2026-09-25')
  await page.reload()
  await expect(min).toHaveValue('01/09/2026')
  await expect(max).toHaveValue('25/09/2026')

  await min.fill('26/09/2026')
  await expect(page.getByRole('alert')).toContainText('La fecha mínima no puede ser posterior')
  await min.fill('31/09/2026')
  await expect(page.getByRole('alert')).toContainText('Las fechas deben ser válidas')
  await min.fill('')
  await max.fill('')
  await expect(page.getByText('PED-0', { exact: true })).toBeVisible()
  await expect.poll(() => requests.at(-1)?.searchParams.get('dateMax')).toBe('')
  expect(requests.at(-1)?.searchParams.get('dateMin')).toBe('')
})
