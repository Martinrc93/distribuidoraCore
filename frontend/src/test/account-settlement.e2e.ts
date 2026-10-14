import { expect, test } from '@playwright/test'

for (const width of [320, 640, 1280]) {
  test(`settles a customer with mixed payments at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 768 })
    await page.addInitScript(() => {
      sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    })
    let settled = false
    let releasePayment!: () => void
    const customer = { id: 'customer-1', name: 'Almacén Norte', balance: 1000, status: 'ACTIVE' }
    await page.route(/^https?:\/\/[^/]+\/api\//, async route => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/settlement')) {
        expect(route.request().postDataJSON()).toEqual({ payments: [
          { method: 'CASH', amount: 400 }, { method: 'BANK_TRANSFER', amount: 600, transferReference: 'TR-123' },
        ] })
        await new Promise<void>(resolve => { releasePayment = resolve })
        settled = true
        return route.fulfill({ status: 201, json: { balanceAfter: 0 } })
      }
      if (path === '/api/customers/customer-1') return route.fulfill({ json: customer })
      if (path === '/api/customers/filter-options') return route.fulfill({ json: { sellers: [] } })
      if (path === '/api/zones') return route.fulfill({ json: [] })
      return route.fulfill({ json: { content: path === '/api/customers' && !settled ? [customer] : [], page: 0, size: 20, totalElements: settled ? 0 : 1, totalPages: 1 } })
    })
    await page.goto('/customers?hasBalance=true')
    const trigger = page.getByRole('button', { name: 'Saldar cuenta de Almacén Norte' })
    await trigger.click()
    const dialog = page.getByRole('dialog', { name: 'Saldar cuenta' })
    await expect(dialog.getByLabel('Medio de pago')).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await trigger.click()
    await dialog.getByLabel('Importe del pago').focus()
    await expect(dialog.getByLabel('Importe del pago')).toHaveValue('1000')
    await dialog.getByLabel('Importe del pago').fill('400')
    await dialog.getByRole('button', { name: 'Agregar pago' }).click()
    await dialog.getByLabel('Medio de pago').nth(1).selectOption('BANK_TRANSFER')
    await dialog.getByLabel('Importe del pago').nth(1).fill('600')
    await dialog.getByLabel('Número de transferencia').fill('TR-123')
    await dialog.getByRole('button', { name: 'Confirmar pago' }).focus()
    await page.keyboard.press('Tab')
    await expect(dialog.getByRole('button', { name: 'Cerrar pago de cuenta corriente' })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(dialog.getByRole('button', { name: 'Confirmar pago' })).toBeFocused()
    const bounds = await dialog.boundingBox()
    expect(bounds!.width).toBeLessThanOrEqual(width)
    expect(bounds!.height).toBeLessThanOrEqual(768)
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await expect(dialog.getByRole('button', { name: 'Confirmar pago' })).toBeInViewport()
    await page.screenshot({ path: testInfo.outputPath(`payment-${width}.png`) })
    await dialog.getByRole('button', { name: 'Confirmar pago' }).click()
    await expect(dialog.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    await page.keyboard.press('Escape')
    await expect(dialog).toBeVisible()
    releasePayment()
    await expect(page.getByRole('status')).toContainText('Cuenta corriente saldada correctamente.')
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('textbox', { name: 'Buscar clientes' })).toBeFocused()
    await expect(trigger).toHaveCount(0)
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  })
}
