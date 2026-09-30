import { expect, test } from '@playwright/test'

test('customer filters combine, persist and fit on mobile', async ({ page }, testInfo) => {
  await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken',
    `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`))
  const requests: URL[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/customers/filter-options') return route.fulfill({ json: { sellers: [{ id: 'seller-1', name: 'Lucía' }, { id: 'seller-2', name: 'Ana' }] } })
    if (url.pathname === '/api/customers') {
      requests.push(url)
      const currentPage = Number(url.searchParams.get('page'))
      return route.fulfill({ json: { content: [{ id: `customer-${currentPage}`, name: `Almacén ${currentPage}`, seller: 'Lucía', balance: 100, status: url.searchParams.get('status') || 'ACTIVE' }], page: currentPage, size: 20, totalElements: 21, totalPages: 2 } })
    }
    return route.fulfill({ json: url.pathname === '/api/zones' ? [] : { content: [] } })
  })
  await page.goto('/customers')
  const seller = page.getByRole('combobox', { name: 'Buscar por vendedor' })
  const balance = page.getByLabel('Cuenta corriente', { exact: true })
  const status = page.getByLabel('Estado', { exact: true })
  const search = page.getByLabel('Buscar clientes', { exact: true })
  await expect(seller).toBeEnabled()
  await expect(status).toHaveValue('ACTIVE')
  await expect.poll(() => requests.at(-1)?.searchParams.get('status')).toBe('ACTIVE')
  await search.focus()
  await page.keyboard.press('Tab')
  await expect(seller).toBeFocused()
  await seller.fill('LUCIA')
  await expect(page.getByRole('option', { name: 'Ana', exact: true })).toHaveCount(0)
  await seller.press('ArrowDown')
  await seller.press('ArrowDown')
  await seller.press('Enter')
  await expect.poll(() => requests.at(-1)?.searchParams.get('sellerId')).toBe('seller-1')
  await balance.selectOption('true')
  await status.selectOption('INACTIVE')
  await search.fill('Almacén')
  await expect.poll(() => requests.at(-1)?.searchParams.get('search')).toBe('Almacén')
  expect(requests.at(-1)?.searchParams.get('sellerId')).toBe('seller-1')
  expect(requests.at(-1)?.searchParams.get('hasBalance')).toBe('true')
  expect(requests.at(-1)?.searchParams.get('status')).toBe('INACTIVE')
  await page.getByRole('button', { name: 'Siguiente' }).click()
  await expect.poll(() => requests.at(-1)?.searchParams.get('page')).toBe('1')
  await status.selectOption('ALL')
  await expect.poll(() => requests.at(-1)?.searchParams.get('page')).toBe('0')
  expect(requests.at(-1)?.searchParams.get('status')).toBe('')
  await page.reload()
  await expect(status).toHaveValue('ALL')
  await expect(seller).toHaveValue('Lucía')
  await expect(balance).toHaveValue('true')
  await expect(search).toHaveValue('Almacén')
  await page.screenshot({ path: testInfo.outputPath('customers-filters.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  if (page.viewportSize()!.width <= 640) {
    const boxes = await Promise.all([search, seller, balance, status].map((control) => control.boundingBox()))
    for (let index = 1; index < boxes.length; index++) {
      expect(boxes[index]!.y).toBeGreaterThan(boxes[index - 1]!.y)
      expect(Math.abs(boxes[index]!.width - boxes[0]!.width)).toBeLessThan(2)
    }
  }
})
