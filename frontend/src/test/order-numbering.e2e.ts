import { expect, test } from '@playwright/test'

for (const width of [320, 760, 1440]) {
  test(`customer heading and numeric order remain readable at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`))
    let customer = 'Almacén Norte'
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const path = new URL(route.request().url()).pathname
      const detail = {
        order: { id: 'order-1', number: '000100004', customerId: 'customer-1', customer, seller: 'Lucía', status: 'CONFIRMED', subtotal: 300, discount: 0, total: 300, customerBalance: 300, date: '2026-10-08T15:00:00Z' },
        items: [{ productId: 'product-1', productName: 'Harina', quantity: 2, unitPrice: 150, lineTotal: 300, priceListId: 'list-1', priceListCode: 'GENERAL', lineDiscountPercent: 0 }],
        sale: { id: 'sale-1', number: 'SAL-1', status: 'CONFIRMED', total: 300, paid: 0, balance: 300, date: '2026-10-08T15:00:00Z' },
        payments: [], deliveryAttempts: [], account: { debit: 300, credit: 0, net: 300 },
      }
      if (path === '/api/orders/order-1') return route.fulfill({ json: detail })
      if (path === '/api/customers/customer-1') return route.fulfill({ json: { id: 'customer-1', number: '0001', name: customer, status: 'ACTIVE', balance: 300 } })
      if (path === '/api/customers') return route.fulfill({ json: { content: [{ id: 'customer-1', number: '0001', name: customer, status: 'ACTIVE', balance: 300 }], page: 0, size: 20, totalElements: 1, totalPages: 1 } })
      return route.fulfill({ json: { content: [], sellers: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
    })
    await page.goto('/orders/order-1')
    await expect(page.getByRole('heading', { level: 1, name: customer })).toBeVisible()
    const subtitle = page.locator('.order-customer-header p')
    await expect(subtitle).toContainText('Pedido 000100004')
    const geometry = await page.locator('.order-customer-header').evaluate((node) => {
      const title = node.querySelector('h1')!
      const subtitle = node.querySelector('p')!
      return { titleSize: parseFloat(getComputedStyle(title).fontSize), subtitleSize: parseFloat(getComputedStyle(subtitle).fontSize), titleBottom: title.getBoundingClientRect().bottom, subtitleTop: subtitle.getBoundingClientRect().top }
    })
    expect(geometry.titleSize).toBeGreaterThan(geometry.subtitleSize)
    expect(geometry.subtitleSize).toBe(13)
    expect(geometry.subtitleTop).toBeGreaterThanOrEqual(geometry.titleBottom)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`order-heading-${width}.png`), fullPage: true })
    customer = 'NombreDeCliente'.repeat(12)
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: customer })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.goto('/customers')
    await expect(page.getByText('Cliente 0001', { exact: true })).toBeVisible()
    await page.getByRole('link', { name: `Ver cliente ${customer}`, exact: true }).click()
    await expect(page.getByText('0001', { exact: true })).toBeVisible()
  })
}
