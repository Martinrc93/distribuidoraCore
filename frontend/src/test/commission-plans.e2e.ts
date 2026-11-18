import { expect, test, type Page } from '@playwright/test'

const path = '/api/settings/commission-plans'
const planId = 'plan-1'
const sellerId = 'vendedor-2'
const statementPath = `/api/commissions/sellers/${sellerId}`
type Plan = { id: string; name: string; version: number; rules: Array<{ scope: string; targetId: string | null; kind: string; value: string }> }

async function setup(page: Page, admin: boolean, dark = false) {
  await page.addInitScript(({ admin, dark }) => {
    const authorities = admin ? ['ADMIN_ALL'] : ['ORDER_CREATE']
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
    if (dark) localStorage.setItem('distribuidora.theme', 'dark')
  }, { admin, dark })
  const state = {
    plan: undefined as Plan | undefined, assignment: null as string | null, requests: [] as string[], errors: [] as string[],
    paid: false, payoutTokens: [] as string[], currentToken: 'saldo-inicial',
  }
  page.on('pageerror', (error) => state.errors.push(error.message))
  await page.route((url) => url.pathname.startsWith('/api/'), (route) => {
    const request = route.request()
    const { pathname, searchParams } = new URL(request.url())
    state.requests.push(pathname)
    if (pathname === '/api/settings/credit-limit') return route.fulfill({ json: { enabled: false, creditLimit: null } })
    if (pathname === '/api/brands') return route.fulfill({ json: [{ id: 'marca-1', name: 'Marlboro' }, { id: 'marca-2', name: 'Philip' }] })
    if (pathname === statementPath) return route.fulfill({ json: {
      seller: 'Lucía Vendedora',
      pending: { token: state.currentToken, entryCount: state.paid ? 0 : 1, amount: state.paid ? '0' : '750.1250' },
      periodAmount: '750.1250',
      movements: {
        content: [{ id: 'commission-1', orderId: 'order-1', orderNumber: 'P-001', product: 'Artículo de prueba',
          amount: '750.1250', returned: false, occurredAt: '2026-11-02T12:00:00Z', paidAt: state.paid ? '2026-11-03T12:00:00Z' : null }],
        totalElements: 1, totalPages: 1, page: 0, size: 20,
      },
    } })
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
      ? [{ id: planId, name: state.plan.name, ruleCount: state.plan.rules.length, sellerCount: state.assignment ? 1 : 0 }] : [] })
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
    const state = await setup(page, true, width === 390)
    await page.goto('/admin/settings')
    await page.getByRole('link', { name: 'Comisiones', exact: true }).click()
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

    const seller = page.getByRole('combobox', { name: 'Vendedor', exact: true })
    await seller.fill('Lucia')
    await page.getByRole('option', { name: 'Lucía Vendedora' }).click()
    const plan = page.getByRole('combobox', { name: 'Plan de comisión', exact: true })
    await expect(plan).toHaveValue('')
    await plan.selectOption(planId)
    const save = page.getByRole('button', { name: 'Guardar asignación' })
    await save.click()
    await expect(page.getByText('Asignación guardada.', { exact: true })).toBeVisible()
    expect(state.assignment).toBe(planId)
    await plan.selectOption('')
    await save.click()
    await expect.poll(() => state.assignment).toBeNull()
    await expect(save).toBeDisabled()
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
  expect(state.requests).not.toContain(statementPath)
})

for (const width of [320, 1440]) {
  test(`el administrador confirma el pago y revisa cambios de saldo a ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page, true, width === 320)
    await page.goto('/admin/settings/commissions')
    await page.getByRole('link', { name: 'Liquidación de comisiones' }).click()
    await page.getByRole('combobox', { name: 'Vendedor', exact: true }).fill('Lucia')
    await page.getByRole('option', { name: 'Lucía Vendedora' }).click()
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
    await expect(page.getByText(/^Liquidado el /)).toBeVisible()
    await expect(pay).toBeDisabled()
    expect(state.payoutTokens).toEqual(['saldo-inicial', 'saldo-actualizado'])
  })
}
