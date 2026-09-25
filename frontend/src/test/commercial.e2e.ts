import { expect, test } from '@playwright/test'

test('admin can create a customer, price a product, confirm an order and open its sale', async ({ page }, testInfo) => {
  const suffix = `${testInfo.project.name}-${Date.now()}`
  const customerName = `E2E ${suffix}`
  const productName = `000 E2E product ${suffix}`
  const sku = `E2E-${suffix.replaceAll(/[^A-Za-z0-9]/g, '').slice(-24)}`

  await page.goto('/login')
  await page.keyboard.press('Tab')
  await expect(page.getByLabel('Email')).toBeFocused()
  const emailFocusOutline = await page.getByLabel('Email').evaluate((input) => getComputedStyle(input).outlineWidth)
  expect(Number.parseFloat(emailFocusOutline)).toBeGreaterThan(0)
  await page.getByLabel('Email').fill('playwright-admin@example.test')
  await page.getByLabel('Contraseña').fill('Playwright-E2E-Password-2026!')
  await page.getByRole('button', { name: 'Ingresar' }).click()
  await expect(page).toHaveURL(/\/orders$/)

  await page.goto('/customers')
  await page.getByRole('button', { name: '+ Nuevo cliente' }).first().click()
  await page.getByLabel('Razón social').fill(customerName)
  await page.getByRole('button', { name: 'Guardar cliente' }).click()
  await expect(page.getByRole('status')).toContainText('Cliente creado correctamente.')

  await page.goto('/products')
  await page.getByRole('button', { name: '+ Nuevo producto' }).first().click()
  await page.getByLabel('SKU').fill(sku)
  await page.getByLabel('Nombre', { exact: true }).fill(productName)
  await page.getByLabel('Categoría', { exact: true }).fill('Almacén')
  await page.getByLabel('Presentación').fill('Unidad')
  await page.getByLabel('Costo').fill('5')
  const initialPrices = page.getByLabel(/^Precio para /)
  await expect(initialPrices.first()).toBeVisible()
  for (let index = 0; index < await initialPrices.count(); index += 1) {
    await initialPrices.nth(index).fill('25')
  }
  const productResponse = page.waitForResponse((response) => response.url().endsWith('/api/products') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Guardar producto' }).click()
  const createdProductResponse = await productResponse
  expect(createdProductResponse.ok()).toBeTruthy()
  await expect(page.getByRole('status')).toContainText('Producto creado correctamente.')

  await page.goto('/inventory')
  await page.getByRole('button', { name: `Ajustar stock de ${productName}` }).click()
  await page.getByLabel('Cantidad de ajuste').fill('10')
  await page.getByLabel('Motivo').fill('Stock inicial E2E')
  await page.getByRole('button', { name: 'Confirmar ajuste' }).click()
  await expect(page.getByRole('status')).toContainText('Ajuste de inventario registrado.')

  const productListResponse = page.waitForResponse((response) => response.url().includes('/api/products?page=0&size=20') && response.request().method() === 'GET')
  await page.goto('/orders/new')
  const productList = await productListResponse
  expect(productList.ok()).toBeTruthy()
  const listedProducts = (await productList.json()).content as Array<{ id: string; name: string }>
  const createdProduct = listedProducts.find((product) => product.name === productName)
  expect(createdProduct).toBeDefined()
  await page.getByLabel('Cliente').selectOption({ label: customerName })
  const list = page.getByLabel('Lista de precios')
  const generalOption = list.locator('option').filter({ hasText: 'GENERAL' }).first()
  await list.selectOption(await generalOption.getAttribute('value') ?? '')
  const depot = page.getByLabel('Depósito para el pedido')
  const centralOption = depot.locator('option').filter({ hasText: 'CENTRAL' }).first()
  await depot.selectOption(await centralOption.getAttribute('value') ?? '')
  const productSelect = page.getByRole('combobox', { name: 'Producto', exact: true })
  await productSelect.selectOption(createdProduct!.id)
  await page.getByRole('button', { name: 'Agregar producto' }).click()

  const orderResponse = page.waitForResponse((response) => response.url().endsWith('/api/orders/confirm') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Confirmar pedido' }).click()
  expect((await orderResponse).ok()).toBeTruthy()
  await expect(page.getByRole('heading', { name: /ORD-/ })).toBeVisible()
  await page.getByRole('link', { name: 'Ver pedido' }).click()
  await expect(page.getByText(productName)).toBeVisible()

  await page.goto('/sales')
  await page.getByLabel('Buscar ventas').fill(customerName)
  await expect(page.getByText(customerName)).toBeVisible()
  await page.getByRole('button', { name: 'Ver venta' }).click()
  await expect(page.getByRole('heading', { name: /Venta SAL-/ })).toBeVisible()
  await expect(page.getByText(productName)).toBeVisible()

  if (testInfo.project.name.startsWith('mobile')) {
    await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
    const width = await page.evaluate(() => ({ viewport: window.innerWidth, document: document.documentElement.scrollWidth }))
    expect(width.document).toBeLessThanOrEqual(width.viewport)
  }
})
