import { expect, test, type Page } from '@playwright/test'

const path = '/api/settings/commission-plans'
const planId = 'plan-1'
const sellerId = 'vendedor-2'
const statementPath = `/api/commissions/sellers/${sellerId}`
const resellerId = 'revendedor-1'
const resellerStatementPath = `/api/commissions/resellers/${resellerId}`
type Plan = { id: string; name: string; version: number; rules: Array<{ scope: string; targetId: string | null; kind: string; value: string }> }

async function setup(page: Page, admin: boolean, dark = false) {
  await page.addInitScript(({ admin, dark }) => {
    const authorities = admin ? ['ADMIN_ALL'] : ['ORDER_CREATE']
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
    if (dark) localStorage.setItem('distribuidora.theme', 'dark')
  }, { admin, dark })
  const state = {
    plan: undefined as Plan | undefined, assignment: null as string | null, resellerAssignment: null as string | null, requests: [] as string[], errors: [] as string[],
    paid: false, payoutTokens: [] as string[], currentToken: 'saldo-inicial', resellerPaid: false,
  }
  page.on('pageerror', (error) => state.errors.push(error.message))
  await page.route((url) => url.pathname.startsWith('/api/'), (route) => {
    const request = route.request()
    const { pathname, searchParams } = new URL(request.url())
    state.requests.push(pathname)
    if (pathname === '/api/settings/credit-limit') return route.fulfill({ json: { enabled: false, creditLimit: null } })
    if (pathname === '/api/brands') return route.fulfill({ json: [{ id: 'marca-1', name: 'Marlboro' }, { id: 'marca-2', name: 'Philip' }] })
    if (pathname === '/api/commissions/balances') {
      const status = searchParams.get('status')
      const content = [
        { id: sellerId, name: 'Lucía Vendedora', beneficiary: 'SELLER', paidAmount: state.paid ? '750.1250' : '0',
          pendingAmount: state.paid ? '0' : '750.1250', paidCount: state.paid ? 1 : 0, pendingCount: state.paid ? 0 : 1 },
        { id: resellerId, name: 'Distribuidora Sur', beneficiary: 'RESELLER', paidAmount: '1200',
          pendingAmount: '90.0000', paidCount: 2, pendingCount: 2 },
      ].filter(row => status === 'ALL' || (status === 'PENDING' ? row.pendingCount > 0 : row.pendingCount === 0))
      return route.fulfill({ json: { content, totalPages: content.length ? 1 : 0, totalElements: content.length, page: 0, size: 20 } })
    }
    if (pathname === statementPath) return route.fulfill({ json: {
      name: 'Lucía Vendedora',
      pending: { token: state.currentToken, entryCount: state.paid ? 0 : 1, amount: state.paid ? '0' : '750.1250' },
      periodAmount: '750.1250',
      movements: {
        content: [{ id: 'commission-1', orderId: 'order-1', orderNumber: 'P-001', customer: 'Kiosco Centro', product: 'Artículo de prueba',
          amount: '750.1250', returned: false, occurredAt: '2026-11-02T12:00:00Z', paidAt: state.paid ? '2026-11-03T12:00:00Z' : null }],
        totalElements: 1, totalPages: 1, page: 0, size: 20,
      },
    } })
    if (pathname === resellerStatementPath) return route.fulfill({ json: {
      name: 'Distribuidora Sur',
      pending: { token: 'saldo-revendedor', entryCount: state.resellerPaid ? 0 : 2, amount: state.resellerPaid ? '0' : '90.0000' },
      periodAmount: '90.0000',
      movements: {
        content: [
          { id: 'commission-2', orderId: 'order-2', orderNumber: 'P-002', customer: 'Kiosco Centro con un nombre comercial muy largo',
            product: 'Yerba mate 1 kg', amount: '60.0000', returned: false, occurredAt: '2026-11-02T12:00:00Z', paidAt: null },
          { id: 'commission-3', orderId: 'order-3', orderNumber: 'P-003', customer: 'Distribuidora Sur',
            product: 'Azúcar 1 kg', amount: '30.0000', returned: false, occurredAt: '2026-11-03T12:00:00Z', paidAt: null },
        ],
        totalElements: 2, totalPages: 1, page: 0, size: 20,
      },
    } })
    if (pathname === `${resellerStatementPath}/settlements`) {
      state.payoutTokens.push(request.postDataJSON().token)
      state.resellerPaid = true
      return route.fulfill({ status: 201, json: { id: 'settlement-2', amount: '90.0000' } })
    }
    if (pathname === '/api/customers' && searchParams.get('kind') === 'RESELLER') return route.fulfill({ json: {
      content: [{ id: resellerId, name: 'Distribuidora Sur', status: 'ACTIVE', kind: 'RESELLER' }],
      totalPages: 1, totalElements: 1, page: 0, size: 100,
    } })
    if (pathname === `${path}/reseller-assignments/${resellerId}`) {
      if (request.method() === 'PUT') state.resellerAssignment = request.postDataJSON().planId
      return route.fulfill({ json: { customerId: resellerId, planId: state.resellerAssignment } })
    }
    if (pathname === `${statementPath}/settlements`) {
      const { token } = request.postDataJSON()
      state.payoutTokens.push(token)
      if (token !== state.currentToken) return route.fulfill({ status: 409, json: {} })
      state.paid = true
      return route.fulfill({ status: 201, json: { id: 'settlement-1', amount: '750.1250' } })
    }
    if (pathname === '/api/sellers') {
      const second = searchParams.get('page') === '1'
      return route.fulfill({ json: {
        content: [second ? { id: sellerId, displayName: 'Lucía Vendedora', status: 'ACTIVE' } : { id: 'vendedor-1', displayName: 'Ana Vendedora', status: 'ACTIVE' }],
        totalPages: 2, totalElements: 2, page: second ? 1 : 0, size: 100,
      } })
    }
    if (pathname === path && request.method() === 'POST') {
      state.plan = { ...request.postDataJSON(), id: planId, version: 1 }
      return route.fulfill({ status: 201, json: state.plan })
    }
    if (pathname === path) return route.fulfill({ json: state.plan
      ? [{ id: planId, name: state.plan.name, ruleCount: state.plan.rules.length, sellerCount: state.assignment ? 1 : 0,
        resellerCount: state.resellerAssignment ? 1 : 0 }] : [] })
    if (pathname === `${path}/assignments`) {
      const content = [
        ...(state.assignment ? [{ beneficiaryId: sellerId, type: 'SELLER', name: 'Lucía Vendedora', status: 'ACTIVE', planName: state.plan!.name }] : []),
        ...(state.resellerAssignment ? [{ beneficiaryId: resellerId, type: 'RESELLER', name: 'Distribuidora Sur', status: 'ACTIVE', planName: state.plan!.name }] : []),
      ]
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: content.length ? 1 : 0, page: 0, size: 20 } })
    }
    if (pathname === `${path}/${planId}`) return route.fulfill({ json: state.plan })
    if (pathname === `${path}/assignments/${sellerId}`) {
      if (request.method() === 'PUT') {
        const { planId: selected, expectedPlanId } = request.postDataJSON()
        if (expectedPlanId !== state.assignment) return route.fulfill({ status: 409, json: {} })
        state.assignment = selected
      }
      return route.fulfill({ json: { sellerId, planId: state.assignment } })
    }
    return route.fulfill({ json: { content: [], totalPages: 0, totalElements: 0, page: 0, size: 20 } })
  })
  return state
}

for (const width of [320, 390, 1440]) {
  test(`guarda un plan reutilizable y administra quién cobra comisión a ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page, true, width !== 320)
    await page.goto('/admin/settings')
    await expect(page.getByRole('heading', { name: 'Configuración', exact: true })).toBeVisible()
    const directory = page.getByRole('navigation', { name: 'Secciones de Configuración' })
    const credit = directory.getByRole('link', { name: /^Límite de crédito/ })
    await expect(credit).toContainText('Desactivado')
    const rules = (await directory.getByRole('heading', { name: 'Reglas comerciales' }).boundingBox())!
    const entry = (await credit.boundingBox())!
    if (width > 760) expect(entry.x).toBeGreaterThan(rules.x)
    else expect(entry.y).toBeGreaterThan(rules.y)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`configuracion-${width}.png`) })

    await credit.press('Enter')
    await expect(page).toHaveURL(/\/admin\/settings\/credit-limit$/)
    await expect(page.getByRole('group', { name: 'Límite de crédito' })).toBeFocused()
    const limit = page.getByRole('textbox', { name: 'Límite de crédito' })
    await expect(limit).toHaveCount(0)
    await page.getByRole('checkbox', { name: 'Activar límite global de crédito' }).check()
    await expect(limit).toBeVisible()
    await expect(page.getByText('Sin guardar', { exact: true })).toBeVisible()
    await limit.fill('2500')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('link', { name: 'Volver a Configuración' }).click()
    await expect(credit).toContainText('Sin guardar')
    await credit.click()
    await expect(limit).toHaveValue('2500')
    await page.getByRole('link', { name: 'Volver a Configuración' }).click()
    await directory.getByRole('link', { name: /^Planes de comisiones/ }).click()
    const editor = page.getByRole('form', { name: 'Asignar comisión' })
    const beneficiaryType = editor.getByRole('combobox', { name: 'Tipo de beneficiario' })
    const plan = editor.getByRole('combobox', { name: 'Plan de comisión', exact: true })
    const assigned = page.locator('.panel').filter({ has: page.getByRole('heading', { name: 'Planes asignados' }) })
    await expect(plan).toBeDisabled()
    await expect(plan.locator('option:checked')).toHaveText('Primero creá un plan')
    await expect(assigned.getByText('Todavía no hay planes asignados.')).toBeVisible()
    const plansPanel = page.locator('.panel').filter({ has: page.getByRole('heading', { name: 'Planes de comisión', exact: true }) })
    const assignmentPanel = page.locator('.panel').filter({ has: editor })
    const planBounds = (await plansPanel.boundingBox())!
    for (const panel of [assignmentPanel, assigned]) {
      const bounds = (await panel.boundingBox())!
      expect(bounds.x).toBeCloseTo(planBounds.x)
      expect(bounds.width).toBeCloseTo(planBounds.width)
    }
    const newPlan = page.getByRole('button', { name: '+ Nuevo plan' })
    await newPlan.click()
    const form = page.getByRole('dialog', { name: 'Nuevo plan de comisión' })
    await expect(form.getByLabel('Nombre del plan')).toBeFocused()
    await form.getByLabel('Nombre del plan').fill('Comercial minorista')
    await form.getByRole('group', { name: 'Regla 1' }).getByLabel('Porcentaje (%)').fill('2,125')
    await form.getByRole('button', { name: 'Agregar regla' }).click()
    const exception = form.getByRole('group', { name: 'Regla 2' })
    await exception.getByLabel('Alcance').selectOption('BRAND')
    await exception.getByRole('combobox', { name: 'Marca' }).click()
    await page.getByRole('option', { name: 'Marlboro' }).click()
    await exception.getByLabel('Tipo de comisión').selectOption('FIXED')
    await exception.getByLabel('Importe fijo ($)').fill('150,005')
    expect(await form.evaluate((dialog) => dialog.scrollWidth <= dialog.clientWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`plan-comision-${width}.png`) })
    await form.getByRole('button', { name: 'Guardar plan', exact: true }).click()
    await expect(page.getByText('Plan guardado.', { exact: true })).toBeVisible()
    await expect(newPlan).toBeFocused()
    expect(state.plan?.rules).toEqual([
      { scope: 'DEFAULT', targetId: null, kind: 'PERCENT', value: '2.125' },
      { scope: 'BRAND', targetId: 'marca-1', kind: 'FIXED', value: '150.005' },
    ])

    const seller = editor.getByRole('combobox', { name: 'Vendedor', exact: true })
    const save = editor.getByRole('button', { name: 'Guardar asignación' })
    await expect(plan.locator('option:checked')).toHaveText('Seleccioná un vendedor')
    await expect(save).toBeDisabled()
    const [typeBox, sellerBox, planBox, saveBox] = await Promise.all([beneficiaryType.boundingBox(), seller.boundingBox(), plan.boundingBox(), save.boundingBox()])
    if (width > 760) {
      expect(sellerBox!.y).toBeCloseTo(typeBox!.y)
      expect(planBox!.y).toBeCloseTo(sellerBox!.y)
      expect(saveBox!.y).toBeCloseTo(sellerBox!.y)
    } else {
      expect(sellerBox!.y).toBeGreaterThan(typeBox!.y)
      expect(planBox!.y).toBeGreaterThan(sellerBox!.y)
      expect(saveBox!.y).toBeGreaterThan(planBox!.y)
    }
    await editor.screenshot({ path: info.outputPath(`asignar-comision-${width}.png`) })
    await seller.fill('Lucia')
    await page.getByRole('option', { name: 'Lucía Vendedora' }).click()
    await expect(plan).toHaveValue('')
    await expect(plan.locator('option:checked')).toHaveText('Sin comisión')
    await plan.selectOption(planId)
    await save.click()
    await expect(editor.getByText('Asignación guardada.', { exact: true })).toBeVisible()
    expect(state.assignment).toBe(planId)
    await expect(assigned.getByText('Lucía Vendedora', { exact: true })).toBeVisible()

    await beneficiaryType.selectOption('RESELLER')
    await expect(seller).toHaveCount(0)
    await editor.getByRole('combobox', { name: 'Revendedor', exact: true }).fill('Sur')
    await page.getByRole('option', { name: 'Distribuidora Sur' }).click()
    await plan.selectOption(planId)
    await save.click()
    await expect.poll(() => state.resellerAssignment).toBe(planId)
    await expect(assigned.getByText('Distribuidora Sur', { exact: true })).toBeVisible()
    await expect(assigned.getByText('Revendedor', { exact: true })).toBeVisible()
    await expect(assigned.getByText('Vendedor', { exact: true })).toBeVisible()
    await assigned.screenshot({ path: info.outputPath(`planes-asignados-${width}.png`) })

    await assigned.getByRole('button', { name: 'Editar asignación de Lucía Vendedora' }).click()
    await expect(beneficiaryType).toHaveValue('SELLER')
    await expect(beneficiaryType).toBeFocused()
    await expect(plan).toHaveValue(planId)
    await plan.selectOption('')
    await beneficiaryType.selectOption('RESELLER')
    await page.getByRole('dialog', { name: '¿Descartar los cambios?' }).getByRole('button', { name: 'Seguir editando' }).click()
    await expect(beneficiaryType).toHaveValue('SELLER')
    await expect(plan).toHaveValue('')
    await save.click()
    await expect.poll(() => state.assignment).toBeNull()
    await expect(save).toBeDisabled()
    await expect(assigned.getByText('Lucía Vendedora', { exact: true })).toHaveCount(0)
    await expect(assigned.getByText('Distribuidora Sur', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Editar plan Comercial minorista' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(state.errors).toEqual([])
  })
}

test('la configuración de comisiones queda reservada a administradores', async ({ page }) => {
  const state = await setup(page, false)
  await page.goto('/admin/settings/commissions')
  await expect(page).toHaveURL(/\/orders$/)
  expect(state.requests.some((request) => request.startsWith(path))).toBe(false)
  await page.goto('/admin/settings/commissions/settlements')
  await expect(page).toHaveURL(/\/orders$/)
  await page.goto('/commission-settlements')
  await expect(page).toHaveURL(/\/orders$/)
  expect(state.requests).not.toContain(statementPath)
})

for (const [type, beneficiaryFilter] of [
  ['reseller', `type=reseller&resellerId=${resellerId}`],
  ['seller', `sellerId=${sellerId}`],
]) {
  test(`un enlace anterior de liquidaciones conserva el beneficiario ${type} y el período`, async ({ page }) => {
    await setup(page, true)
    const filters = `${beneficiaryFilter}&dateMin=01%2F10%2F2025&dateMax=31%2F10%2F2025`
    await page.goto(`/admin/settings/commissions/settlements?${filters}`)
    await expect(page).toHaveURL((url) => url.pathname === '/commission-settlements' && url.search === `?${filters}`)
    await expect(page.getByRole('combobox', { name: 'Comisiones de' })).toHaveValue(type)
  })
}

for (const width of [320, 1440]) {
  test(`el administrador confirma el pago y revisa cambios de saldo a ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page, true, width === 320)
    await page.goto('/admin/settings/commissions')
    await page.getByRole('link', { name: 'Liquidación de comisiones' }).click()
    const filter = page.getByRole('combobox', { name: 'Estado de pago' })
    await expect(page.getByText('Pagado y pendiente', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await filter.selectOption('PAID')
    await expect(page.getByText('No hay beneficiarios en este resultado.')).toBeVisible()
    await filter.selectOption('PENDING')
    await page.getByRole('link', { name: 'Ver movimientos de Lucía Vendedora' }).click()
    const pay = page.getByRole('button', { name: 'Registrar pago al vendedor' })
    await expect(page.getByText('Pendiente', { exact: true })).toBeVisible()
    expect(state.payoutTokens).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`liquidacion-comisiones-${width}.png`), fullPage: true })

    await pay.click()
    const confirmation = page.getByRole('dialog', { name: 'Confirmar pago de comisión' })
    await expect(confirmation.getByRole('button', { name: 'Volver' })).toBeFocused()
    await expect(confirmation.getByText(/750,125.*Lucía Vendedora/)).toBeVisible()
    state.currentToken = 'saldo-actualizado'
    await confirmation.getByRole('button', { name: 'Registrar pago', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('El saldo pendiente cambió')
    expect(state.paid).toBe(false)
    await pay.click()
    await confirmation.getByRole('button', { name: 'Registrar pago', exact: true }).click()
    await expect(page.getByRole('status')).toContainText('registrado para Lucía Vendedora')
    await expect(page.getByText(/^Pagado el /)).toBeVisible()
    await expect(pay).toBeDisabled()
    expect(state.payoutTokens).toEqual(['saldo-inicial', 'saldo-actualizado'])
    await page.getByRole('button', { name: 'Volver al resumen' }).click()
    await expect(filter).toHaveValue('PENDING')
    await expect(page.getByRole('link', { name: 'Ver movimientos de Distribuidora Sur' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Ver movimientos de Lucía Vendedora' })).toHaveCount(0)
    await filter.selectOption('PAID')
    const paid = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Ver movimientos de Lucía Vendedora' }) })
    await expect(paid.getByText('Pagado', { exact: true })).toBeVisible()
  })

  test(`el administrador liquida las comisiones de un revendedor a ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page, true, width === 320)
    await page.goto('/admin/settings')
    if (width <= 1050) await page.getByRole('button', { name: 'Abrir menú' }).click()
    const navigation = page.getByRole('navigation', { name: 'Navegación principal' })
    await navigation.getByRole('button', { name: 'Operación' }).click()
    await navigation.getByRole('link', { name: 'Liquidaciones' }).click()
    await expect(page).toHaveURL(/\/commission-settlements$/)
    const type = page.getByRole('combobox', { name: 'Comisiones de' })
    const reseller = page.getByRole('combobox', { name: 'Revendedor', exact: true })
    await expect(type).toHaveValue('reseller')
    const [typeBox, resellerBox, fromBox, toBox] = await Promise.all([
      type.boundingBox(), reseller.boundingBox(),
      page.getByRole('textbox', { name: 'Desde', exact: true }).boundingBox(),
      page.getByRole('textbox', { name: 'Hasta', exact: true }).boundingBox(),
    ])
    expect(toBox!.y).toBeCloseTo(fromBox!.y)
    if (width > 760) {
      expect(resellerBox!.y).toBeCloseTo(typeBox!.y)
      expect(fromBox!.y).toBeCloseTo(typeBox!.y)
    } else {
      expect(resellerBox!.y).toBeGreaterThan(typeBox!.y)
      expect(fromBox!.y).toBeGreaterThan(resellerBox!.y)
    }
    await reseller.fill('Sur')
    await page.getByRole('option', { name: 'Distribuidora Sur' }).click()
    await expect(page.getByText('Kiosco Centro con un nombre comercial muy largo')).toBeVisible()
    expect(state.requests).toContain('/api/customers')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`liquidacion-revendedor-${width}.png`), fullPage: true })

    await page.getByRole('button', { name: 'Registrar pago al revendedor' }).click()
    const confirmation = page.getByRole('dialog', { name: 'Confirmar pago de comisión' })
    await expect(confirmation.getByText(/90.*Distribuidora Sur/)).toBeVisible()
    await confirmation.getByRole('button', { name: 'Registrar pago', exact: true }).click()
    await expect(page.getByRole('status')).toContainText('registrado para Distribuidora Sur')
    expect(state.payoutTokens).toEqual(['saldo-revendedor'])
  })
}
