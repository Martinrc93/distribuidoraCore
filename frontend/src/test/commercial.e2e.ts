import { expect, test } from '@playwright/test'

test('admin can create a customer, price a product, confirm an order and open its sale', async ({ page }, testInfo) => {
  const suffix = `${testInfo.project.name}-${Date.now()}`
  const customerName = `E2E ${suffix}`
  const productDescription = `000 E2E product ${suffix}`
  let productName = productDescription

  await page.goto('/login')
  await page.getByLabel('Email').focus()
  await expect(page.getByLabel('Email')).toBeFocused()
  const emailFocusOutline = await page.getByLabel('Email').evaluate((input) => getComputedStyle(input).outlineWidth)
  expect(Number.parseFloat(emailFocusOutline)).toBeGreaterThan(0)
  await page.getByLabel('Email').fill('playwright-admin@example.test')
  await page.getByLabel('Contraseña', { exact: true }).fill('Playwright-E2E-Password-2026!')
  await page.getByRole('button', { name: 'Ingresar' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  const brandName = `Marca ${suffix}`
  const categoryName = `Rubro ${suffix}`
  await page.goto('/catalog')
  await page.getByRole('button', { name: '+ Nueva marca', exact: true }).first().click()
  await page.getByLabel('Nombre de marca').fill(brandName)
  await page.getByRole('button', { name: 'Guardar marca' }).click()
  await expect(page.getByText(brandName, { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: 'Categorías' }).click()
  await page.getByRole('button', { name: '+ Nueva categoría', exact: true }).first().click()
  await page.getByLabel('Nombre de categoría').fill(categoryName)
  await page.getByRole('button', { name: 'Guardar categoría' }).click()
  await expect(page.getByText(categoryName, { exact: true })).toBeVisible()

  await page.goto('/customers')
  await page.getByRole('button', { name: '+ Nuevo cliente' }).first().click()
  await page.getByLabel('Razón social').fill(customerName)
  await page.getByRole('button', { name: 'Guardar cliente' }).click()
  await expect(page.getByRole('status')).toContainText('Cliente creado correctamente.')

  await page.goto('/products')
  await page.getByRole('button', { name: '+ Nuevo producto' }).first().click()
  await page.getByLabel('Descripción', { exact: true }).fill(productDescription)
  const brand = page.getByRole('combobox', { name: 'Marca', exact: true })
  await brand.selectOption({ label: brandName })
  productName = await page.getByLabel('Nombre', { exact: true }).textContent() ?? ''
  await page.getByRole('combobox', { name: 'Categoría', exact: true }).selectOption({ label: categoryName })
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

  await page.getByLabel('Buscar productos').fill(productName)
  await page.getByRole('button', { name: `Ajustar stock de ${productName}` }).click()
  await page.getByLabel('Cantidad final', { exact: true }).fill('10')
  await page.getByLabel('Motivo (opcional)').fill('Stock inicial E2E')
  await page.getByRole('button', { name: 'Confirmar ajuste' }).click()
  await expect(page.getByText('Ajuste de stock registrado.')).toBeVisible()

  const productListResponse = page.waitForResponse((response) => response.url().includes('/api/products?page=0&size=100&includeStock=true') && response.request().method() === 'GET')
  await page.goto('/orders/new')
  const productList = await productListResponse
  expect(productList.ok()).toBeTruthy()
  const listedProducts = (await productList.json()).content as Array<{ id: string; name: string }>
  const createdProduct = listedProducts.find((product) => product.name === productName)
  expect(createdProduct).toBeDefined()
  await page.getByRole('combobox', { name: 'Cliente', exact: true }).fill(customerName)
  await page.getByRole('option', { name: customerName, exact: true }).click()
  const list = page.getByLabel('Lista de precios')
  const generalOption = list.locator('option').filter({ hasText: 'GENERAL' }).first()
  await list.selectOption(await generalOption.getAttribute('value') ?? '')
  const productSelect = page.getByRole('combobox', { name: 'Producto', exact: true })
  await productSelect.fill(productName)
  await page.getByRole('option', { name: productName, exact: false }).click()
  await page.getByRole('button', { name: 'Agregar producto' }).click()

  const orderResponse = page.waitForResponse((response) => response.url().endsWith('/api/orders/confirm') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Confirmar pedido' }).click()
  const confirmedOrder = await orderResponse
  expect(confirmedOrder.ok()).toBeTruthy()
  const { saleNumber } = await confirmedOrder.json() as { saleNumber: string }
  expect(saleNumber).toMatch(/^\d{9}$/)
  await expect(page.getByRole('heading', { name: customerName, exact: true })).toBeVisible()
  await expect(page.locator('.order-customer-header p')).toContainText(/Pedido \d{9}/)
  await page.getByRole('link', { name: 'Ver pedido' }).click()
  await expect(page.getByText(productName)).toBeVisible()

  await page.goto('/sales')
  await page.getByLabel('Buscar ventas').fill(saleNumber)
  await expect(page.getByText(customerName)).toBeVisible()
  await page.getByRole('button', { name: 'Ver venta' }).click()
  await expect(page.getByRole('heading', { name: /^Venta (?:V-)?\d+$/ })).toBeVisible()
  await expect(page.getByText(productName)).toBeVisible()

  if (testInfo.project.name.startsWith('mobile')) {
    const saleDetail = page.getByRole('dialog', { name: 'Detalle de venta' })
    await saleDetail.getByRole('button', { name: 'Cerrar', exact: true }).click()
    await expect(saleDetail).toHaveCount(0)
    await page.getByRole('button', { name: 'Abrir menú' }).click()
    await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
    const width = await page.evaluate(() => ({ viewport: window.innerWidth, document: document.documentElement.scrollWidth }))
    expect(width.document).toBeLessThanOrEqual(width.viewport)
  }
})
