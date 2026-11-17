import { expect, test, type Page } from '@playwright/test'

const permissions = [
  { code: 'ADMIN_ALL', description: 'Acceso administrativo completo', adminOnly: true },
  { code: 'ORDER_CREATE', description: 'Crear pedidos', adminOnly: false },
  { code: 'ORDER_PREPARE', description: 'Preparar pedidos y descontar su stock', adminOnly: false },
  { code: 'ORDER_PRICE_LIST_CHANGE', description: 'Cambiar la lista de precios de un pedido', adminOnly: false },
  { code: 'ORDER_REVIEW', description: 'Confirmar la revisión administrativa de pedidos de vendedores', adminOnly: true },
  { code: 'SALE_DELIVER', description: 'Entregar ventas', adminOnly: false },
  { code: 'SALE_PAYMENT', description: 'Registrar cobros', adminOnly: false },
  { code: 'STOCK_ADJUST', description: 'Ajustar stock', adminOnly: false },
  { code: 'USER_MANAGE', description: 'Administrar usuarios', adminOnly: true },
]
async function setup(page: Page, isAdmin = true) {
  await page.addInitScript(admin => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: admin ? ['ADMIN_ALL'] : ['ORDER_CREATE'] }))}.signature`), isAdmin)
  const state = { saved: [] as string[], body: undefined as Record<string, unknown> | undefined }
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    const roles = [
      { id: 'a', code: 'ADMIN', description: 'Administrador', permissions: ['ADMIN_ALL', 'USER_MANAGE'], requiredPermissions: ['ADMIN_ALL'], assignedToCurrentUser: true },
      { id: 's', code: 'SELLER', description: 'Vendedor', permissions: state.saved.length ? state.saved : ['ORDER_CREATE'], requiredPermissions: [], assignedToCurrentUser: false },
    ]
    if (route.request().method() === 'PUT') {
      state.body = route.request().postDataJSON()
      state.saved = state.body!.permissionCodes as string[]
      const role = path.includes('/ADMIN/') ? roles[0] : roles[1]
      return route.fulfill({ json: { ...role, permissions: state.saved, currentSessionRevoked: role.assignedToCurrentUser } })
    }
    return route.fulfill({ json: path === '/api/roles' ? roles : path === '/api/permissions' ? permissions : { content: [], totalElements: 0, totalPages: 0 } })
  })
  return state
}

async function fits(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(await page.evaluate(() => innerWidth))
  const modal = page.getByRole('dialog', { name: 'Permisos de Vendedor' })
  const bounds = await modal.boundingBox()
  expect(bounds!.width).toBeLessThanOrEqual(await page.evaluate(() => innerWidth))
  expect(bounds!.height).toBeLessThanOrEqual(await page.evaluate(() => innerHeight))
}

for (const width of [320, 390, 1280]) {
  test(`role editing, confirmation and discard work at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    if (width === 390) await page.addInitScript(() => localStorage.setItem('distribuidora.theme', 'dark'))
    const state = await setup(page)
    await page.goto('/admin/roles')
    await expect(page.getByRole('heading', { name: 'Roles y permisos', exact: true })).toBeVisible()
    if (width > 1050) await expect(page.getByRole('link', { name: 'Roles y permisos', exact: true })).toHaveAttribute('aria-current', 'page')
    else {
      await page.getByRole('button', { name: 'Abrir menú' }).click()
      await expect(page.getByRole('dialog', { name: 'Menú principal' }).getByRole('link', { name: 'Roles y permisos' })).toHaveAttribute('aria-current', 'page')
      await page.keyboard.press('Escape')
    }
    await page.getByRole('button', { name: 'Editar permisos de Vendedor' }).click()
    let form = page.getByRole('dialog', { name: 'Permisos de Vendedor' })
    const create = form.getByRole('checkbox', { name: /Crear pedidos/ })
    await expect(create).toBeFocused()
    await expect(form.getByRole('checkbox', { name: /Acceso administrativo completo/ })).toBeDisabled()
    await fits(page)
    await page.screenshot({ path: info.outputPath(`permissions-${width}.png`), fullPage: true })
    await form.getByRole('checkbox', { name: /Preparar pedidos/ }).check()
    await page.keyboard.press('Escape')
    await page.getByRole('dialog', { name: '¿Descartar los cambios?' }).getByRole('button', { name: 'Seguir editando' }).click()
    await expect(form.getByRole('checkbox', { name: /Preparar pedidos/ })).toBeChecked()
    await form.getByRole('button', { name: 'Revisar cambios' }).click()
    const confirmation = page.getByRole('dialog', { name: 'Guardar permisos de Vendedor' })
    await expect(confirmation.getByRole('button', { name: 'Volver' })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(confirmation.getByRole('button', { name: 'Guardar permisos' })).toBeFocused()
    await page.screenshot({ path: info.outputPath(`confirmation-${width}.png`), fullPage: true })
    await confirmation.getByRole('button', { name: 'Guardar permisos' }).click()
    await expect(form).toHaveCount(0)
    await expect(page.getByRole('status')).toContainText('Permisos de Vendedor guardados')
    expect(state.body).toEqual({ permissionCodes: ['ORDER_CREATE', 'ORDER_PREPARE'], expectedPermissionCodes: ['ORDER_CREATE'] })
    await expect(page.getByRole('button', { name: 'Editar permisos de Vendedor' })).toBeFocused()
    await page.getByRole('button', { name: 'Editar permisos de Vendedor' }).click()
    form = page.getByRole('dialog', { name: 'Permisos de Vendedor' })
    await expect(form.getByRole('checkbox', { name: /Preparar pedidos/ })).toBeChecked()
    await fits(page)
  })
}

test('changing the current role clears the session and explains the new login', async ({ page }) => {
  await setup(page)
  await page.goto('/admin/roles')
  await page.getByRole('button', { name: 'Editar permisos de Administrador' }).click()
  await page.getByRole('checkbox', { name: /Administrar usuarios/ }).uncheck()
  await page.getByRole('button', { name: 'Revisar cambios' }).click()
  await page.getByRole('dialog', { name: 'Guardar permisos de Administrador' }).getByRole('button', { name: 'Guardar permisos' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('status')).toContainText('Los permisos de tu rol se guardaron')
  expect(await page.evaluate(() => sessionStorage.getItem('distribuidora.accessToken'))).toBeNull()
})

test('sellers cannot enter the role editor or see its navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page, false)
  let reads = 0
  page.on('request', request => { if (/\/api\/(roles|permissions)/.test(request.url())) reads++ })
  await page.goto('/admin/roles')
  await expect(page).toHaveURL(/\/orders$/)
  await page.getByRole('button', { name: 'Abrir menú' }).click()
  await expect(page.getByRole('link', { name: 'Roles y permisos' })).toHaveCount(0)
  expect(reads).toBe(0)
})

async function setupCustomRoles(page: Page, ownSession = false) {
  await setup(page)
  if (ownSession) await page.addInitScript(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ sub: 'user-1', authorities: ['ADMIN_ALL'] }))}.signature`))
  const custom = { id: 'custom-role', code: 'LOGISTICS', description: 'Logística', permissions: ['ORDER_PREPARE'], requiredPermissions: [], assignedToCurrentUser: false, requiresSellerProfile: true }
  const roles = [
    { ...custom, id: 'admin-role', code: 'ADMIN', description: 'Administrador', permissions: ['ADMIN_ALL'], requiresSellerProfile: false },
    { ...custom, id: 'seller-role', code: 'SELLER', description: 'Vendedor', permissions: ['ORDER_CREATE'] },
  ]
  if (ownSession) roles.push(custom)
  const state = { created: undefined as Record<string, unknown> | undefined, assigned: undefined as Record<string, unknown> | undefined }
  const account = { id: 'user-1', email: 'user@example.test', name: 'Usuario asignado', roles: ownSession ? 'ADMIN,SELLER' : 'SELLER', status: 'ACTIVE', sellerStatus: 'ACTIVE' }
  await page.route(url => url.pathname === '/api/roles' || url.pathname.startsWith('/api/users'), async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'POST' && path === '/api/roles') {
      state.created = route.request().postDataJSON(); roles.push(custom)
      return route.fulfill({ status: 201, json: custom })
    }
    if (route.request().method() === 'PUT') {
      state.assigned = route.request().postDataJSON(); account.roles = String(state.assigned!.role)
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ json: path === '/api/roles' ? roles : { content: [account], totalElements: 1, totalPages: 1 } })
  })
  return state
}

for (const width of [320, 390, 1280]) {
  test(`custom role creation and user assignment work at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    if (width === 390) await page.addInitScript(() => localStorage.setItem('distribuidora.theme', 'dark'))
    const state = await setupCustomRoles(page)
    await page.goto('/admin/roles')
    await page.getByRole('button', { name: '+ Crear rol', exact: true }).click()
    const form = page.getByRole('dialog', { name: 'Crear rol personalizado' })
    await expect(form.getByLabel('Nombre del rol')).toBeFocused()
    await form.getByLabel('Nombre del rol').fill('Logística')
    await form.getByLabel('Código del rol', { exact: true }).fill('logistics')
    await expect(form.getByRole('checkbox', { name: /Acceso administrativo completo/ })).toBeDisabled()
    await form.getByRole('checkbox', { name: /Preparar pedidos/ }).check()
    expect((await form.boundingBox())!.width).toBeLessThanOrEqual(width)
    expect((await form.boundingBox())!.height).toBeLessThanOrEqual(900)
    await page.screenshot({ path: info.outputPath(`custom-role-${width}.png`), fullPage: true })
    await form.getByRole('button', { name: 'Revisar nuevo rol' }).click()
    expect(state.created).toBeUndefined()
    await page.getByRole('dialog', { name: 'Crear rol Logística' }).getByRole('button', { name: 'Crear rol', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Editar permisos de Logística' })).toBeVisible()
    expect(state.created).toEqual({ code: 'LOGISTICS', description: 'Logística', permissionCodes: ['ORDER_PREPARE'] })
    await page.goto('/admin/users')
    await page.getByRole('button', { name: 'Cambiar rol de user@example.test' }).click()
    const assignment = page.getByRole('dialog', { name: 'Cambiar rol de user@example.test' })
    await expect(assignment.getByLabel('Nuevo rol')).toBeFocused()
    await expect(assignment.getByRole('option', { name: 'Administrador' })).toHaveJSProperty('disabled', true)
    await assignment.getByLabel('Nuevo rol').selectOption('LOGISTICS')
    await page.screenshot({ path: info.outputPath(`assign-role-${width}.png`), fullPage: true })
    await assignment.getByRole('button', { name: 'Revisar cambio' }).click()
    expect(state.assigned).toBeUndefined()
    await page.getByRole('button', { name: 'Confirmar cambio de rol' }).click()
    await expect(page.getByRole('status')).toContainText('Rol actualizado')
    await expect(page.getByText('LOGISTICS', { exact: true })).toBeVisible()
    expect(state.assigned).toEqual({ role: 'LOGISTICS' })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
  })
}

test('changing the own user role clears the session and explains the login', async ({ page }) => {
  await setupCustomRoles(page, true)
  await page.goto('/admin/users')
  await page.getByRole('button', { name: 'Cambiar rol de user@example.test' }).click()
  await page.getByLabel('Nuevo rol').selectOption('LOGISTICS')
  await page.getByRole('button', { name: 'Revisar cambio' }).click()
  await page.getByRole('button', { name: 'Confirmar cambio de rol' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('status')).toContainText('El rol de tu usuario se actualizó')
  expect(await page.evaluate(() => sessionStorage.getItem('distribuidora.accessToken'))).toBeNull()
})
