import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ sub: 'navigation-admin', authorities: ['ADMIN_ALL'] }))}.signature`)
    sessionStorage.setItem('distribuidora.refreshToken', 'test-refresh-token')
  })
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/logout') return route.fulfill({ status: 204 })
    if (path === '/api/brands' || path === '/api/categories') return route.fulfill({ json: [] })
    return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
  })
})

const logoPath = '/api/settings/business-logo'
const logoPng = 'iVBORw0KGgoAAAANSUhEUgAAAGAAAABACAYAAADlNHIOAAABvElEQVR4nO2cwVEDMRAED8eEwoEoIAqIAsJZcjLlB5R9lmTdSasZnaa/tqtc3Vrt+eNlEWJmnkre9Pz2cvb/Ksfj5/P7od/sGyTeP8Qp9YLktyPnMhpA8tuTcnoXQPL9iLm9CSD5/qwdJ3eA6MN/AJ3+fly71gSAUQAwCgBGAcAoABgFAKMAYKYPYB9fCoCWj4ww/QT8cYmACDFtAEvI7h1hygCWkRzeX7t+l9NosqzyhKKX7lABbLUkvZdm79NPH6D1JDBdPfQBrEDwlgiM8mkDGNk9PVUA2yi/dlKQp58ywB5ygpnl0wWwClmxz7LLpwpgBbL2RGCHIoBtEFcaYYTTTxMgR0xWzSQwyacIYDtlsYkcMoBVntStERijwQL0XpiBUD7FFVQry2NSDh+g9ZIMq88wC4cH8HpCCavfCqW/HaYK4H3vhwEngWYHhAFkDR1gpB9HhwyQkhwmlt/9CrrInl04xQ4Y5Qnl0EtY8smegmZFAcAoABgFAKMAYBQAjAKAUQAwCsASoOQP5kQbrl1rAsDcBNAU+LN2fDcBiuBHzG30ClKE9qScJneAIrQj51J/XeyIDrEQywN+Aed7tXbtoOwuAAAAAElFTkSuQmCC'

for (const width of [320, 1280]) {
  test(`business logo can be previewed, saved and removed at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    let stored: string | null = null
    await page.route(`**${logoPath}`, async (route) => {
      const request = route.request()
      if (request.method() === 'PUT') stored = request.postDataJSON().content
      if (request.method() === 'DELETE') {
        stored = null
        return route.fulfill({ status: 204 })
      }
      return route.fulfill({ json: { content: stored } })
    })
    await page.route('**/api/settings/business-profile', (route) => route.fulfill({ json: { name: 'Distribuidora', address: '', phone: '' } }))
    await page.goto('/admin/settings/business-profile')
    const contactSection = page.locator('summary').filter({ hasText: 'Datos de contacto' })
    const logoSection = page.locator('summary').filter({ hasText: 'Logo del comercio' })
    const phone = page.getByRole('textbox', { name: 'Teléfono' })
    const chooser = page.getByLabel('Seleccionar imagen')
    const chooseImage = page.getByText('Seleccionar imagen', { exact: true })
    const preview = page.getByRole('img', { name: 'Vista previa del logo' })
    const shellLogo = page.locator(width <= 1050 ? '.topbar img.brand-mark' : '.sidebar img.brand-mark')
    await expect(shellLogo).toHaveCount(0)
    await expect(phone).toBeHidden()
    await expect(chooseImage).toBeHidden()
    await page.screenshot({ path: info.outputPath(`comercio-plegado-${width}.png`) })

    await contactSection.press('Enter')
    await phone.fill('011 5555-0128')
    await logoSection.press('Space')
    await expect(chooseImage).toBeVisible()
    await expect(logoSection).toBeFocused()
    await expect(phone).toBeHidden()
    await expect(contactSection).toContainText('Sin guardar')

    await chooser.setInputFiles({ name: 'logo.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') })
    await expect(page.getByRole('alert')).toContainText('PNG o JPG')
    await chooser.setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: Buffer.from(logoPng, 'base64') })
    await expect(preview).toHaveJSProperty('naturalWidth', 96)
    await expect(shellLogo).toHaveCount(0)
    await logoSection.click()
    await expect(chooseImage).toBeHidden()
    await expect(logoSection).toContainText('Sin guardar')
    await contactSection.click()
    await expect(phone).toHaveValue('011 5555-0128')
    await page.getByRole('link', { name: 'Volver a Configuración' }).click()
    const details = page.getByRole('link', { name: /^Datos del comercio/ })
    await expect(details).toContainText('Sin guardar')
    await details.click()
    await logoSection.click()
    await expect(preview).toHaveJSProperty('naturalWidth', 96)
    await page.getByRole('button', { name: 'Guardar logo' }).click()
    await expect(page.getByText('Logo guardado.')).toBeVisible()
    await expect(shellLogo).toHaveJSProperty('naturalWidth', 96)
    expect(stored).toBe(logoPng)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: info.outputPath(`logo-comercio-${width}.png`), fullPage: true })

    if (width <= 1050) {
      await page.getByRole('button', { name: 'Abrir menú' }).click()
      await expect(page.locator('.navigation-drawer img.brand-mark')).toHaveJSProperty('naturalWidth', 96)
      await page.keyboard.press('Escape')
    }
    await page.getByRole('button', { name: 'Quitar logo' }).click()
    await expect(shellLogo).toHaveCount(1)
    await page.getByRole('button', { name: 'Guardar logo' }).click()
    await expect(page.getByText('Logo eliminado.')).toBeVisible()
    await expect(shellLogo).toHaveCount(0)
    expect(stored).toBeNull()
  })
}

for (const width of [320, 1280]) {
  test(`operation shortcuts can be assigned, used and removed at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 768 })
    await page.route('**/api/settings/credit-limit', (route) => route.fulfill({ json: { creditLimit: null, enabled: false } }))
    await page.goto('/admin/settings')
    await page.getByRole('link', { name: /^Atajos de teclado/ }).click()
    await expect(page.getByRole('heading', { name: 'Atajos de teclado' })).toBeVisible()
    const backLink = page.getByRole('link', { name: 'Volver a Configuración' })
    const backBounds = (await backLink.boundingBox())!
    const optionsBounds = (await page.locator('main .panel').boundingBox())!
    expect(Math.abs(backBounds.x + backBounds.width - optionsBounds.x - optionsBounds.width)).toBeLessThan(1)
    expect(backBounds.y + backBounds.height).toBeLessThan(optionsBounds.y)
    if (width > 760) {
      const headerBounds = (await page.getByRole('group', { name: 'Atajos de teclado' }).boundingBox())!
      expect(Math.abs(backBounds.y - headerBounds.y)).toBeLessThan(1)
    }
    for (const operation of ['Resumen', 'Dashboard', 'Pedidos', 'Ventas', 'Compras', 'Clientes', 'Gastos', 'Liquidaciones']) {
      await expect(page.getByRole('textbox', { name: operation, exact: true })).toHaveValue('')
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)

    const sales = page.getByRole('textbox', { name: 'Ventas', exact: true })
    const customers = page.getByRole('textbox', { name: 'Clientes', exact: true })
    await sales.press('Alt+Shift+V')
    await expect(sales).toHaveValue('Alt+Mayús+V')
    await customers.press('Alt+Shift+V')
    await expect(customers).toHaveValue('')
    await expect(page.getByRole('alert')).toHaveText('Este atajo ya está asignado a Ventas.')
    await expect(page).toHaveURL(/\/admin\/settings\/shortcuts$/)

    const nav = page.getByRole('navigation', { name: 'Navegación principal' })
    if (width <= 1050) await page.getByRole('button', { name: 'Abrir menú' }).click()
    await nav.getByRole('button', { name: 'Operación' }).click()
    await expect(nav.getByRole('link', { name: 'Ventas', exact: true })).toHaveAttribute('aria-keyshortcuts', 'Alt+Shift+V')
    if (width <= 1050) {
      await page.keyboard.press('Alt+Shift+V')
      await expect(page.getByRole('dialog', { name: 'Menú principal' })).toBeVisible()
      await page.keyboard.press('Escape')
    }

    await page.goto('/orders')
    await page.getByRole('textbox', { name: 'Buscar pedidos' }).press('Alt+Shift+V')
    await expect(page).toHaveURL(/\/orders$/)
    await page.getByRole('heading', { name: 'Pedidos', exact: true }).click()
    await page.keyboard.press('Alt+Shift+V')
    await expect(page).toHaveURL(/\/sales$/)
    await expect(page.getByRole('main')).toBeFocused()

    await page.goto('/admin/settings/shortcuts')
    await expect(sales).toHaveValue('Alt+Mayús+V')
    await page.getByRole('button', { name: 'Quitar atajo de Ventas' }).click()
    await expect(sales).toHaveValue('')
    await expect(sales).toBeFocused()
    await page.getByRole('heading', { name: 'Atajos de teclado' }).click()
    await page.keyboard.press('Alt+Shift+V')
    await expect(page).toHaveURL(/\/admin\/settings\/shortcuts$/)
    await backLink.click()
    await expect(page).toHaveURL(/\/admin\/settings$/)
  })
}

for (const width of [320, 1280]) {
  test(`theme switches and persists at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 768 })
    await page.goto('/orders')
    const toggle = page.getByRole('button', { name: 'Cambiar a modo oscuro' })
    await expect(toggle).toBeVisible()
    await toggle.focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(16, 27, 32)')
    await expect(page.locator('.topbar')).toHaveCSS('background-color', 'rgb(25, 41, 47)')
    await expect(page.getByRole('button', { name: 'Cambiar a modo claro' })).toHaveCSS('background-color', 'rgb(25, 41, 47)')
    const toggleBounds = await page.getByRole('button', { name: 'Cambiar a modo claro' }).boundingBox()
    expect(toggleBounds!.height).toBeGreaterThanOrEqual(44)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: testInfo.outputPath(`dark-${width}.png`), fullPage: true })
    await page.reload()
    await expect(page.getByRole('button', { name: 'Cambiar a modo claro' })).toHaveAttribute('aria-pressed', 'true')
    if (width === 320) {
      await page.getByRole('button', { name: 'Abrir menú' }).click()
      await expect(page.getByRole('dialog')).toHaveCSS('background-color', 'rgb(18, 35, 42)')
      await page.screenshot({ path: testInfo.outputPath('dark-menu.png') })
      await page.keyboard.press('Escape')
    }
    await page.getByRole('button', { name: 'Cambiar a modo claro' }).click()
    await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(243, 245, 242)')
    await page.screenshot({ path: testInfo.outputPath(`light-${width}.png`), fullPage: true })
  })
}

for (const width of [320, 390, 640, 760, 761, 1024, 1050, 1051, 1280, 1366, 1440, 1920]) {
  test(`navigation fits and remains usable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: width === 1920 ? 1080 : 768 })
    await page.goto('/payments')
    await expect(page).toHaveURL(/\/orders$/)
    await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    const nav = page.getByRole('navigation', { name: 'Navegación principal' })
    if (width <= 1050) {
      await expect(nav).toHaveCount(0)
      const trigger = page.getByRole('button', { name: 'Abrir menú' })
      await trigger.click()
      const dialog = page.getByRole('dialog', { name: 'Menú principal' })
      await expect(dialog).toBeVisible()
      await expect(dialog.getByRole('link', { name: 'Pagos y deuda' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'Cerrar menú' })).toBeFocused()
      await expect(page.getByRole('button', { name: 'Salir' })).toBeVisible()
      await expect(dialog.getByRole('link', { name: 'Pedidos', exact: true })).toHaveAttribute('aria-current', 'page')
      const bounds = await dialog.boundingBox()
      expect(bounds!.width).toBeLessThanOrEqual(width)
      expect(bounds!.height).toBeLessThanOrEqual(768)
      // Native modality contains keyboard focus in both directions.
      await page.keyboard.press('Shift+Tab')
      await expect(dialog.getByRole('button', { name: 'Salir' })).toBeFocused()
      await page.keyboard.press('Tab')
      await expect(dialog.getByRole('button', { name: 'Cerrar menú' })).toBeFocused()
      await page.screenshot({ path: testInfo.outputPath(`menu-${width}.png`) })
      await page.keyboard.press('Escape')
      await expect(dialog).toHaveCount(0)
      await expect(trigger).toBeFocused()
      expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
      await trigger.click()
      await nav.getByRole('button', { name: 'Catálogo', exact: true }).click()
      await page.getByRole('link', { name: 'Stock', exact: true }).click()
      await expect(page).toHaveURL(/\/products$/)
      await expect(dialog).toHaveCount(0)
      await expect(page.locator('.compact-brand')).toContainText('Stock')
    } else {
      await expect(nav).toBeVisible()
      await expect(nav.getByRole('link', { name: 'Pagos y deuda' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'Abrir menú' })).toHaveCount(0)
      await nav.getByRole('button', { name: 'Administración', exact: true }).click()
      await nav.getByRole('link', { name: 'Auditoría' }).scrollIntoViewIfNeeded()
      await expect(nav.getByRole('link', { name: 'Auditoría' })).toBeInViewport()
      await expect(page.getByRole('button', { name: 'Salir' })).toBeInViewport()
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: testInfo.outputPath(`page-${width}.png`), fullPage: true })
  })
}

test('drawer supports backdrop, resize, short landscape screens and signing out', async ({ page }) => {
  await page.setViewportSize({ width: 760, height: 360 })
  await page.goto('/orders')
  const trigger = page.getByRole('button', { name: 'Abrir menú' })
  const dialog = page.getByRole('dialog', { name: 'Menú principal' })
  await trigger.click()
  await dialog.getByRole('button', { name: 'Administración', exact: true }).click()
  await dialog.getByRole('link', { name: 'Auditoría' }).scrollIntoViewIfNeeded()
  await expect(dialog.getByRole('link', { name: 'Auditoría' })).toBeInViewport()
  await expect(dialog.getByRole('button', { name: 'Salir' })).toBeInViewport()
  await page.mouse.click(740, 180)
  await expect(dialog).toHaveCount(0)
  await expect(trigger).toBeFocused()
  await trigger.click()
  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Pedidos', exact: true })).toBeFocused()
  expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(dialog).toHaveCount(0)
  await trigger.click()
  await dialog.getByRole('button', { name: 'Catálogo', exact: true }).click()
  await dialog.getByRole('link', { name: 'Stock', exact: true }).click()
  await expect(page).toHaveURL(/\/products$/)
  await expect(dialog).toHaveCount(0)
  await trigger.click()
  await expect(dialog).toBeVisible()
  await page.goBack()
  await expect(page).toHaveURL(/\/orders$/)
  await expect(dialog).toHaveCount(0)
  await trigger.click()
  await dialog.getByRole('button', { name: 'Salir' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { name: 'Ingresar' })).toBeVisible()
  expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
})

test('compact navigation respects permissions and reduced motion', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/orders')
  await page.evaluate(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['SELLER'] }))}.signature`))
  // Init scripts reset the session on reload, so use an in-app route transition.
  await page.getByRole('button', { name: 'Abrir menú' }).click()
  await page.getByRole('link', { name: 'Ventas', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Ventas', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Abrir menú' }).click()
  const dialog = page.getByRole('dialog', { name: 'Menú principal' })
  await expect(dialog.getByRole('link', { name: 'Resumen' })).toHaveCount(0)
  await expect(dialog.getByRole('link', { name: 'Usuarios' })).toHaveCount(0)
  await expect(dialog.getByRole('link', { name: 'Clientes' })).toBeVisible()
  await expect(dialog.getByRole('link', { name: 'Pagos y deuda' })).toHaveCount(0)
  for (const name of ['Stock', 'Marcas y categorías', 'Listas de precios']) {
    await expect(dialog.getByRole('link', { name })).toHaveCount(0)
  }
  await expect(dialog.getByText('Catálogo', { exact: true })).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: 'Administración', exact: true })).toHaveCount(0)
  await expect(dialog.getByRole('link', { name: 'Pedidos' })).toBeVisible()
  await dialog.getByRole('link', { name: 'Clientes' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page).toHaveURL(/\/customers$/)
  await expect(page.getByRole('heading', { name: 'Clientes', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '+ Nuevo cliente' })).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: 'Buscar por vendedor' })).toHaveCount(0)
})

for (const width of [390, 1440]) {
  test(`groups collapse independently with keyboard access and larger text at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/orders')
    if (width <= 1050) await page.getByRole('button', { name: 'Abrir menú' }).click()
    const nav = page.getByRole('navigation', { name: 'Navegación principal' })
    const operation = nav.getByRole('button', { name: 'Operación', exact: true })
    const catalog = nav.getByRole('button', { name: 'Catálogo', exact: true })
    const administration = nav.getByRole('button', { name: 'Administración', exact: true })
    await expect(operation).toHaveAttribute('aria-expanded', 'true')
    await expect(catalog).toHaveAttribute('aria-expanded', 'false')
    await expect(administration).toHaveAttribute('aria-expanded', 'false')
    expect(await operation.evaluate((element) => getComputedStyle(element).fontSize)).toBe('14px')
    expect(await nav.getByRole('link', { name: 'Pedidos', exact: true }).evaluate((element) => getComputedStyle(element).fontSize)).toBe('14px')
    await operation.click()
    await expect(nav.getByRole('link', { name: 'Pedidos', exact: true })).toHaveCount(0)
    await page.keyboard.press('Tab')
    await expect(catalog).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(nav.getByRole('link', { name: 'Stock', exact: true })).toBeVisible()
    await expect(operation).toHaveAttribute('aria-expanded', 'false')
    await expect(administration).toHaveAttribute('aria-expanded', 'false')
    await page.keyboard.press('Space')
    await expect(catalog).toHaveAttribute('aria-expanded', 'false')
    await page.keyboard.press('Tab')
    await expect(administration).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(nav.getByRole('link', { name: 'Usuarios', exact: true })).toBeVisible()
    await expect(catalog).toHaveAttribute('aria-expanded', 'false')
    if (width <= 1050) {
      await page.keyboard.press('Escape')
      await expect(nav).toHaveCount(0)
      await page.getByRole('button', { name: 'Abrir menú' }).click()
      await expect(operation).toHaveAttribute('aria-expanded', 'false')
      await expect(administration).toHaveAttribute('aria-expanded', 'true')
    }
    await nav.getByRole('link', { name: 'Usuarios', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/users$/)
    await expect(page.getByRole('heading', { name: 'Usuarios', exact: true })).toBeVisible()
    if (width <= 1050) await page.getByRole('button', { name: 'Abrir menú' }).click()
    await expect(administration).toHaveAttribute('aria-expanded', 'true')
    await expect(operation).toHaveAttribute('aria-expanded', 'false')
  })
}

test('resizing a drawer with the active group collapsed restores focus to its visible title', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/orders')
  await page.getByRole('button', { name: 'Abrir menú' }).click()
  await page.getByRole('button', { name: 'Operación', exact: true }).click()
  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Operación', exact: true })).toBeFocused()
  await expect(page.getByRole('link', { name: 'Pedidos', exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
})
