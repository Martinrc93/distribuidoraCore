import { expect, test } from '@playwright/test'

test('empty daily results let users show historical orders with one click', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date('2026-09-30T12:00:00Z'))
  await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken', 'test-access-token'))
  await page.route('**/api/orders?*', async (route) => {
    const params = new URL(route.request().url()).searchParams
    const filtered = Boolean(params.get('dateMin') || params.get('dateMax') || params.get('search') || params.get('status'))
    await route.fulfill({ json: {
      content: filtered ? [] : [{ id: 'historical', number: 'PED-ANTERIOR', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-19T12:00:00Z' }],
      page: 0, size: 20, totalElements: filtered ? 0 : 1, totalPages: filtered ? 0 : 1,
    } })
  })
  await page.goto('/orders')
  await expect(page.getByRole('heading', { name: 'No hay pedidos para estos filtros' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('orders-empty-daily.png'), fullPage: true })
  await page.getByRole('button', { name: 'Ver todos los pedidos' }).click()
  await expect(page.getByText('PED-ANTERIOR', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Desde', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('Hasta', { exact: true })).toHaveValue('')
  await page.reload()
  await expect(page.getByText('PED-ANTERIOR', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Desde', { exact: true })).toHaveValue('')
})

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
  const min = page.getByLabel('Desde', { exact: true })
  const max = page.getByLabel('Hasta', { exact: true })
  await expect(min).toHaveValue('30/09/2026')
  await expect(max).toHaveValue('30/09/2026')
  await expect(page.getByText('PED-0', { exact: true })).toBeVisible()
  expect(requests.at(-1)?.searchParams.get('dateMin')).toBe('2026-09-30')
  expect(requests.at(-1)?.searchParams.get('dateMax')).toBe('2026-09-30')

  await min.focus()
  await expect(min).toBeFocused()
  expect(await min.evaluate((input) => Number.parseFloat(getComputedStyle(input).outlineWidth))).toBeGreaterThan(0)
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: 'Abrir calendario: Desde' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(max).toBeFocused()

  const moreFilters = page.getByRole('button', { name: /^Más filtros/ })
  if (await moreFilters.isVisible()) {
    await expect(moreFilters).toHaveAttribute('aria-expanded', 'false')
    await moreFilters.click()
    await expect(moreFilters).toHaveAttribute('aria-expanded', 'true')
  }
  const statusBox = await page.getByLabel('Buscar pedidos').boundingBox()
  const minBox = await min.boundingBox()
  if (minBox!.y > statusBox!.y + statusBox!.height) {
    const toolbar = await page.locator('.orders-toolbar').boundingBox()
    expect(minBox!.x).toBeCloseTo(toolbar!.x, 0)
  }
  const widths = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }))
  expect(widths.document).toBeLessThanOrEqual(widths.viewport)
  await page.screenshot({ path: testInfo.outputPath('orders-date-filters.png'), fullPage: true })

  await min.click()
  const calendar = page.getByRole('dialog', { name: /Calendario: Desde/ })
  await expect(calendar).toBeVisible()
  await expect(calendar.getByRole('heading')).toHaveText('Septiembre de 2026')
  await expect(calendar.getByText('lu', { exact: true })).toBeVisible()
  await expect(calendar.getByRole('button', { name: 'Hoy', exact: true })).toBeVisible()
  const calendarBox = await calendar.boundingBox()
  const viewport = page.viewportSize()!
  expect(calendarBox!.x).toBeGreaterThanOrEqual(0)
  expect(calendarBox!.y).toBeGreaterThanOrEqual(0)
  expect(calendarBox!.x + calendarBox!.width).toBeLessThanOrEqual(viewport.width)
  expect(calendarBox!.y + calendarBox!.height).toBeLessThanOrEqual(viewport.height)
  await page.screenshot({ path: testInfo.outputPath('orders-min-calendar.png') })
  await expect(calendar.getByRole('button', { name: /30 de septiembre de 2026/ })).toBeFocused()
  await page.keyboard.press('ArrowLeft')
  await page.keyboard.press('Enter')
  await expect(min).toHaveValue('29/09/2026')
  await expect.poll(() => requests.at(-1)?.searchParams.get('dateMin')).toBe('2026-09-29')
  await page.getByRole('button', { name: 'Abrir calendario: Hasta' }).click()
  await page.getByRole('dialog', { name: /Calendario: Hasta/ }).getByRole('button', { name: /29 de septiembre de 2026/ }).click()
  await expect(max).toHaveValue('29/09/2026')
  await expect.poll(() => requests.at(-1)?.searchParams.get('dateMax')).toBe('2026-09-29')
  await expect(page.getByRole('button', { name: 'Abrir calendario: Hasta' })).toBeFocused()
  await min.click()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(min).toBeFocused()

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
  await page.getByRole('group', { name: 'Estado del pedido' }).getByRole('button', { name: /^Por entregar/ }).click()
  const allDates = page.getByRole('button', { name: 'Todas las fechas' })
  await allDates.click()
  await expect(allDates).toBeFocused()
  await expect(min).toHaveValue('')
  await expect(max).toHaveValue('')
  await expect(page.getByText('PED-0', { exact: true })).toBeVisible()
  // The stage counts ask for one order each; the list itself uses the page size.
  const listRequest = () => requests.filter((url) => url.searchParams.get('size') === '20').at(-1)
  await expect.poll(() => listRequest()?.searchParams.get('dateMax')).toBe('')
  expect(listRequest()?.searchParams.get('dateMin')).toBe('')
  expect(listRequest()?.searchParams.get('status')).toBe('CONFIRMED')
})
