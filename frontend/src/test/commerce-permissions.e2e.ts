import { expect, test } from '@playwright/test'

for (const width of [320, 390, 1280]) for (const extra of ['', 'ORDER_PRICE_OVERRIDE', 'ORDER_DISCOUNT']) {
  test(`assigned editing with ${extra || 'historical values'} at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    await page.addInitScript(({ extra, dark }) => {
      sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ORDER_EDIT', ...(extra ? [extra] : [])] }))}.signature`)
      if (dark) localStorage.setItem('distribuidora.theme', 'dark')
    }, { extra, dark: width === 390 })
    let saved: Record<string, unknown> | undefined
    const details = {
      order: { id: 'o1', number: '000100001', customerId: 'c1', customer: 'Cliente asignado', seller: 'Lucía', status: 'CONFIRMED', subtotal: 24, discount: 3.48, orderDiscountPercent: 5, total: 20.52, customerBalance: 20.52, date: '2026-11-17T12:00:00Z' },
      items: [{ productId: 'p1', productName: 'Harina', quantity: 2, unitPrice: 12, lineTotal: 21.6, priceListId: 'g1', priceListCode: 'GENERAL', lineDiscountPercent: 10 }],
      sale: { id: 's1', number: 'V1', status: 'CONFIRMED', total: 20.52, paid: 0, balance: 20.52 }, payments: [], deliveryAttempts: [], account: { debit: 20.52, credit: 0, net: 20.52 },
    }
    const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/orders/o1') {
        if (route.request().method() === 'PUT') { saved = route.request().postDataJSON(); return route.fulfill({ json: {} }) }
        return route.fulfill({ json: details })
      }
      if (path === '/api/products') return route.fulfill({ json: { content: [{ id: 'p1', name: 'Harina', status: 'ACTIVE' }], totalElements: 1, totalPages: 1 } })
      if (path === '/api/pricing/lists') return route.fulfill({ json: { content: [{ id: 'g1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }], totalElements: 1, totalPages: 1 } })
      if (path === '/api/pricing/resolve-batch') {
        expect(new URL(route.request().url()).searchParams.get('orderId')).toBe('o1')
        return route.fulfill({ json: [{ productId: 'p1', priceListId: 'g1', priceListCode: 'GENERAL', unitPrice: 10 }] })
      }
      return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0 } })
    })
    await page.goto('/orders/o1')
    await expect(page.getByRole('button', { name: 'Cancelar pedido', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Enviar por email o WhatsApp' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Editar pedido', exact: true }).click()
    const price = page.getByLabel('Precio unitario de Harina')
    const discount = page.getByLabel('Descuento de Harina')
    await expect(price).toHaveCount(extra === 'ORDER_PRICE_OVERRIDE' ? 1 : 0)
    await expect(discount).toHaveCount(extra === 'ORDER_DISCOUNT' ? 1 : 0)
    if (extra === 'ORDER_PRICE_OVERRIDE') await price.fill('14')
    if (extra === 'ORDER_DISCOUNT') await discount.fill('8')
    await page.getByLabel('Cantidad de Harina').fill('3')
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: info.outputPath(`edit-${extra || 'readonly'}-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: 'Guardar cambios', exact: true }).click()
    await expect(page.getByRole('status')).toContainText('Pedido actualizado')
    expect(saved).toMatchObject({ priceListId: 'g1', lines: [{ productId: 'p1', quantity: 3, unitPriceOverride: extra === 'ORDER_PRICE_OVERRIDE' ? 14 : 12, lineDiscountPercent: extra === 'ORDER_DISCOUNT' ? 8 : 10 }], orderDiscountPercent: 5 })
    expect(errors).toEqual([])
  })
}
