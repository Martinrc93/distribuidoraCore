import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, extname, join, resolve } from 'node:path'
import { text } from 'node:stream/consumers'
import { chromium, expect } from '@playwright/test'

const folded = (value) => String(value ?? '').trim().toLowerCase()
const browserOptions = { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH }
const screenshots = { animations: 'disabled', caret: 'hide' }

function unique(items, key, section) {
  const values = new Map()
  for (const item of items) {
    const name = folded(key(item))
    assert(name && !values.has(name), `${section} contiene un identificador vacío o repetido.`)
    values.set(name, item)
  }
  return values
}

function requiredString(value, max, field) {
  assert(typeof value === 'string' && value.trim() === value && value.length && value.length <= max, `${field} debe contener entre 1 y ${max} caracteres, sin espacios excedentes.`)
}

function money(value, field) {
  assert(typeof value === 'number' && Number.isFinite(value) && value >= 0 && value < 1e12 && /^\d+(\.\d{1,4})?$/.test(String(value)), `${field} debe ser un importe no negativo de hasta cuatro decimales.`)
}

async function readData(path) {
  const data = JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''))
  const sections = ['zones', 'brands', 'categories', 'suppliers', 'priceLists', 'products', 'sellers', 'customers']
  assert(data && typeof data === 'object' && data.business, 'Se requiere business con los datos del comercio.')
  for (const section of sections) {
    data[section] ??= []
    assert(Array.isArray(data[section]), `${section} debe ser una lista.`)
  }
  for (const [field, max] of Object.entries({ name: 120, address: 220, phone: 40 })) {
    if (field === 'name' || data.business[field]) requiredString(data.business[field], max, `business.${field}`)
  }
  for (const section of ['zones', 'brands', 'categories', 'priceLists']) {
    for (const value of data[section]) requiredString(value, 120, section)
  }
  for (const item of data.suppliers) requiredString(item.name, 160, 'suppliers.name')
  const brands = unique(data.brands, (item) => item, 'brands')
  const categories = unique(data.categories, (item) => item, 'categories')
  const zones = unique(data.zones, (item) => item, 'zones')
  const lists = unique(['GENERAL', ...data.priceLists], (item) => item, 'priceLists')
  const suppliers = unique(data.suppliers, (item) => item.name, 'suppliers')
  const sellers = unique(data.sellers, (item) => item.email, 'sellers')
  unique(data.products, (item) => `${item.brand} ${item.description}`, 'products')
  unique(data.customers, (item) => item.businessName, 'customers')

  for (const seller of data.sellers) {
    requiredString(seller.email, 254, 'sellers.email')
    assert(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(seller.email), 'sellers.email no es válido.')
    requiredString(seller.displayName, 160, 'sellers.displayName')
    assert(typeof seller.passwordEnv === 'string' && /^ONBOARDING_[A-Z0-9_]+$/.test(seller.passwordEnv), 'Indicá una variable ONBOARDING_… en sellers.passwordEnv.')
  }
  for (const product of data.products) {
    requiredString(product.description, 200, 'products.description')
    assert(brands.has(folded(product.brand)) && categories.has(folded(product.category)), 'Un producto referencia una marca o categoría fuera del archivo.')
    assert(Array.isArray(product.suppliers ?? []) && (product.suppliers ?? []).every((name) => suppliers.has(folded(name))), 'Un producto referencia un proveedor fuera del archivo.')
    assert(product.prices && typeof product.prices === 'object' && !Array.isArray(product.prices), 'Cada producto requiere prices.')
    const prices = unique(Object.entries(product.prices), ([name]) => name, 'products.prices')
    assert(prices.has('general'), 'Cada producto requiere un precio GENERAL.')
    money(product.cost, 'products.cost')
    assert(Number.isSafeInteger((product.initialStock ?? 0) * 2) && (product.initialStock ?? 0) >= 0 && typeof (product.initialStock ?? 0) === 'number', 'El stock inicial debe ser no negativo, en múltiplos de 0,5.')
    for (const [list, price] of Object.entries(product.prices)) {
      assert(lists.has(folded(list)), 'Un precio referencia una lista fuera del archivo.')
      money(price, 'products.prices')
      assert(price >= product.cost, 'Un precio inicial es menor al costo.')
    }
  }
  for (const customer of data.customers) {
    requiredString(customer.businessName, 200, 'customers.businessName')
    assert(!customer.zone || zones.has(folded(customer.zone)), 'Un cliente referencia una zona fuera del archivo.')
    assert(!customer.sellerEmail || sellers.has(folded(customer.sellerEmail)), 'Un cliente referencia un vendedor fuera del archivo.')
    assert(!customer.priceList || lists.has(folded(customer.priceList)), 'Un cliente referencia una lista fuera del archivo.')
    assert(customer.openingBalance === undefined && customer.balance === undefined, 'Los saldos anteriores se concilian por separado.')
  }
  if (data.business.logoPath) {
    const logo = resolve(dirname(path), data.business.logoPath)
    const extension = extname(logo).toLowerCase()
    assert(['.png', '.jpg', '.jpeg'].includes(extension), 'El logo debe ser PNG o JPG.')
    const bytes = await readFile(logo)
    assert(bytes.length && bytes.length <= 512 * 1024, 'El logo debe pesar hasta 512 KB.')
    data.business.logo = bytes.toString('base64')
  }
  return data
}

async function run(options) {
  const url = new URL(options.url)
  assert(url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port && url.pathname === '/', 'La URL debe corresponder al frontend local de Compose.')
  const data = options.action === 'load' ? await readData(options.dataPath) : null
  const evidence = join(options.output, '01-verificacion')
  const manual = join(options.output, '02-manual')
  await Promise.all([mkdir(evidence, { recursive: true }), mkdir(manual, { recursive: true })])
  const browser = await chromium.launch(browserOptions)
  let token
  let refreshToken
  let page
  const journal = {}

  async function api(method, path, body) {
    const response = await page.request.fetch(new URL(path, url).href, {
      method,
      headers: { Authorization: `Bearer ${token}` },
      ...(body === undefined ? {} : { data: body }),
    })
    try {
      if (!response.ok()) {
        const error = await response.json().catch(() => ({}))
        const description = error.message ?? error.detail
        throw new Error(`${method} ${path.split('?')[0]} → HTTP ${response.status()}${description ? ` · ${description}` : ''}`)
      }
      const result = await response.text()
      return result ? JSON.parse(result) : undefined
    } finally { await response.dispose() }
  }

  async function all(path) {
    const separator = path.includes('?') ? '&' : '?'
    const pagePath = (number) => `${path}${separator}page=${number}&size=100`
    const result = await api('GET', pagePath(0))
    const items = [...result.content]
    for (let index = 1; index < result.totalPages; index += 1) {
      items.push(...(await api('GET', pagePath(index))).content)
    }
    return items
  }

  async function show(path, heading, value) {
    await page.goto(new URL(path, url).href)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    if (value) await expect(page.getByRole('main').getByText(value, { exact: true }).first()).toBeVisible()
    else await page.locator('main [role="status"]').filter({ hasText: /^Cargando|Consultando/ }).waitFor({ state: 'hidden' })
  }

  async function record(name, details, prepareManual) {
    await page.screenshot({ ...screenshots, path: join(evidence, `${name}.png`) })
    const locator = await prepareManual()
    await locator.screenshot({ ...screenshots, path: join(manual, `${name}.png`),
      style: options.sample ? '' : 'input,textarea,main img, .brand-block img { -webkit-text-security: disc; } img { visibility: hidden; }' })
    journal[name] = details
    await writeFile(join(options.output, 'verificacion.json'), JSON.stringify(journal, null, 2) + '\n')
    process.stdout.write(`Verificada: ${name}\n`)
  }

  async function blank(button) {
    await page.getByRole('button', { name: button, exact: true }).first().click()
    return page.getByRole('dialog').last()
  }

  async function ensure(path, index, name, body) {
    const key = folded(name)
    const existing = index.get(key)
    if (existing) {
      assert(!existing.status || existing.status === 'ACTIVE', `${path} tiene un registro coincidente inactivo.`)
      return existing
    }
    const created = await api('POST', path, body)
    assert(created?.id, `El alta en ${path} no devolvió un identificador.`)
    const entry = { ...body, id: created.id }
    index.set(key, entry)
    return entry
  }

  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, locale: 'es-AR', colorScheme: 'light' })
    await show('/login', 'Ingresar')
    const loginImage = await page.screenshot(screenshots)
    await page.getByLabel('Email').fill(options.email)
    await page.getByLabel('Contraseña', { exact: true }).fill(options.password)
    const login = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/auth/login' && response.request().method() === 'POST')
    await page.getByRole('button', { name: 'Ingresar' }).click()
    const response = await login
    assert(response.ok(), 'La cuenta no pudo iniciar sesión con la contraseña indicada.')
    const session = await response.json()
    token = session.accessToken
    refreshToken = session.refreshToken
    await page.waitForURL('**/dashboard')

    if (options.action === 'check-login') {
      process.stdout.write('Acceso del administrador verificado.\n')
      return
    }

    if (options.action === 'prepare') {
      assert(options.sample, 'Preparar datos de prueba requiere Sample.')
      await api('POST', '/api/users', { email: 'admin-descartado@ejemplo.test', role: 'ADMIN', temporaryPassword: `${randomUUID()}Aa!` })
      await api('POST', '/api/customers', { businessName: 'Cliente descartado' })
      return
    }

    if (options.action === 'reset') {
      const users = await all('/api/users')
      assert(users.length === 1 && folded(users[0].email) === folded(options.email) && users[0].status === 'ACTIVE', 'La base inicial debe contener solo el administrador activo indicado.')
      const empty = ['/api/customers', '/api/products', '/api/orders?dateScope=all', '/api/sales']
      for (const path of empty) assert.equal((await all(path)).length, 0, `${path} tiene datos inesperados.`)
      await show('/admin/users', 'Usuarios', options.email)
      await page.screenshot({ ...screenshots, path: join(evidence, '01-reinicio.png') })
      await writeFile(join(manual, '01-reinicio.png'), loginImage)
      await writeFile(join(options.output, 'verificacion.json'), '{"01-reinicio":{"administradores":1,"clientes":0,"productos":0,"pedidos":0,"ventas":0}}\n')
      process.stdout.write('Verificada: 01-reinicio\n')
      return
    }

    for (const path of ['/api/orders?dateScope=all', '/api/sales']) {
      assert.equal((await all(path)).length, 0, 'La carga inicial requiere una base sin operaciones comerciales.')
    }
    for (const seller of data.sellers) {
      if (!options.sample) requiredString(process.env[seller.passwordEnv], 200, seller.passwordEnv)
      if (!options.sample) assert(process.env[seller.passwordEnv].length >= 8, `${seller.passwordEnv} requiere al menos 8 caracteres.`)
    }
    const savedJournal = await readFile(join(options.output, 'verificacion.json'), 'utf8').catch((error) => {
      if (error.code === 'ENOENT') return '{}'
      throw error
    })
    Object.assign(journal, JSON.parse(savedJournal))

    const profile = { name: data.business.name, address: data.business.address ?? '', phone: data.business.phone ?? '' }
    await api('PUT', '/api/settings/business-profile', profile)
    if (data.business.logo) await api('PUT', '/api/settings/business-logo', { content: data.business.logo })
    const zones = new Map((await api('GET', '/api/zones')).map((item) => [folded(item.name), item]))
    for (const name of data.zones) await ensure('/api/zones', zones, name, { name })
    assert.deepEqual(await api('GET', '/api/settings/business-profile'), profile)
    await show('/admin/settings/business-profile', 'Datos del comercio')
    await page.locator('summary').filter({ hasText: 'Datos de contacto' }).click()
    await expect(page.getByLabel('Nombre del comercio')).toHaveValue(profile.name)
    await record('02-configuracion', { zonas: data.zones.length, logo: Boolean(data.business.logo) }, () => page.getByRole('main'))

    const brands = unique(await api('GET', '/api/brands'), (item) => item.name, 'marcas existentes')
    const categories = unique(await api('GET', '/api/categories'), (item) => item.name, 'categorías existentes')
    const suppliers = unique(await all('/api/suppliers'), (item) => item.name, 'proveedores existentes')
    const lists = unique(await all('/api/pricing/lists'), (item) => item.name, 'listas existentes')
    const general = [...lists.values()].find((item) => item.code === 'GENERAL' && item.status === 'ACTIVE')
    assert(general, 'Falta la lista activa GENERAL.')
    lists.set('general', general)
    for (const name of data.brands) await ensure('/api/brands', brands, name, { name })
    for (const name of data.categories) await ensure('/api/categories', categories, name, { name })
    for (const supplier of data.suppliers) await ensure('/api/suppliers', suppliers, supplier.name, supplier)
    for (const name of data.priceLists) await ensure('/api/pricing/lists', lists, name, { name })
    await show('/catalog', 'Marcas y categorías', data.brands[0])
    await record('03-catalogo', { marcas: data.brands.length, categorias: data.categories.length, proveedores: data.suppliers.length, listas: data.priceLists.length }, () => blank('+ Nueva marca'))

    const products = unique(await all('/api/products?includeStock=true'), (item) => item.name, 'productos existentes')
    const priceChecks = new Map()
    for (const product of data.products) {
      const name = `${product.brand} ${product.description}`
      const prices = Object.entries(product.prices).map(([list, price]) => ({ priceListId: lists.get(folded(list)).id, price }))
      const body = { description: product.description, cost: product.cost, initialStock: product.initialStock ?? 0, prices,
        brandId: brands.get(folded(product.brand)).id, categoryId: categories.get(folded(product.category)).id,
        supplierIds: (product.suppliers ?? []).map((supplier) => suppliers.get(folded(supplier)).id) }
      const saved = await ensure('/api/products', products, name, body)
      for (const price of prices) {
        const checks = priceChecks.get(price.priceListId) ?? new Map()
        checks.set(saved.id, price.price)
        priceChecks.set(price.priceListId, checks)
      }
    }
    const savedProducts = unique(await all('/api/products?includeStock=true'), (item) => item.name, 'productos guardados')
    for (const product of data.products) {
      const saved = savedProducts.get(folded(`${product.brand} ${product.description}`))
      assert(saved && saved.status === 'ACTIVE' && saved.brandId === brands.get(folded(product.brand)).id && saved.categoryId === categories.get(folded(product.category)).id, 'Un producto no coincide con las referencias del archivo.')
      assert.equal(saved.stock, product.initialStock ?? 0, 'El stock inicial no coincide con el archivo.')
      assert.equal(saved.cost, product.cost, 'El costo no coincide con el archivo.')
      assert.deepEqual(saved.suppliers.map((supplier) => supplier.id).sort(), (product.suppliers ?? []).map((name) => suppliers.get(folded(name)).id).sort(), 'Los proveedores del producto no coinciden.')
    }
    for (const [listId, checks] of priceChecks) {
      const prices = new Map((await all(`/api/pricing/lists/${listId}/prices`)).map((row) => [row.productId, row.price]))
      for (const [productId, price] of checks) assert.equal(prices.get(productId), price, 'Un precio inicial no coincide con el archivo.')
    }
    const firstProduct = data.products[0]
    const firstProductName = firstProduct && `${firstProduct.brand} ${firstProduct.description}`
    await show(firstProductName ? `/products?search=${encodeURIComponent(firstProductName)}` : '/products', 'Stock', firstProductName)
    await record('04-productos-y-stock', { productos: data.products.length, listasVerificadas: priceChecks.size }, async () => {
      const form = await blank('+ Nuevo producto')
      await expect(form.getByRole('combobox', { name: 'Marca', exact: true })).toBeEnabled()
      return form
    })

    const users = unique(await all('/api/users'), (item) => item.email, 'usuarios existentes')
    for (const seller of data.sellers) {
      const existing = users.get(folded(seller.email))
      assert(!existing || existing.roles?.split(',').map((role) => role.trim()).includes('SELLER'), 'El correo de un vendedor pertenece a una cuenta con otro rol.')
      await ensure('/api/users', users, seller.email, { email: seller.email, role: 'SELLER', displayName: seller.displayName,
        temporaryPassword: options.sample ? `${randomUUID()}Aa!` : process.env[seller.passwordEnv] })
    }
    const sellers = unique(await all('/api/sellers?status=ACTIVE'), (item) => item.email, 'vendedores guardados')
    for (const seller of data.sellers) {
      const saved = sellers.get(folded(seller.email))
      assert(saved?.status === 'ACTIVE' && saved.userId === users.get(folded(seller.email)).id && saved.displayName === seller.displayName, 'El perfil de vendedor existente es diferente al solicitado.')
    }
    const firstSeller = data.sellers[0]?.displayName
    await show(firstSeller ? `/admin/sellers?search=${encodeURIComponent(firstSeller)}` : '/admin/sellers', 'Vendedores', firstSeller)
    await record('05-vendedores', { vendedores: data.sellers.length }, async () => {
      await show('/admin/users', 'Usuarios')
      const form = await blank('+ Crear usuario')
      await expect(form.getByRole('combobox', { name: 'Rol', exact: true })).toBeEnabled()
      await form.getByRole('button', { name: 'Crear con contraseña provisoria' }).click()
      await expect(form.getByLabel('Contraseña provisoria', { exact: true })).toBeVisible()
      return form
    })

    const customers = unique(await all('/api/customers?status='), (item) => item.name, 'clientes existentes')
    for (const customer of data.customers) {
      const sellerId = customer.sellerEmail ? sellers.get(folded(customer.sellerEmail)).id : null
      const priceListId = customer.priceList ? lists.get(folded(customer.priceList)).id : null
      const zone = customer.zone ? zones.get(folded(customer.zone)).name : null
      await ensure('/api/customers', customers, customer.businessName, {
        businessName: customer.businessName, cuitId: customer.cuitId ?? null, email: customer.email ?? null,
        phone: customer.phone ?? null, address: customer.address ?? null, sellerId, priceListId, zone,
      })
    }
    const savedCustomers = unique(await all('/api/customers?status='), (item) => item.name, 'clientes guardados')
    for (const customer of data.customers) {
      const saved = savedCustomers.get(folded(customer.businessName))
      assert(saved && saved.status === 'ACTIVE', 'Un cliente quedó sin registrar o está inactivo.')
      assert.equal(saved.sellerId ?? null, sellers.get(folded(customer.sellerEmail))?.id ?? null, 'El vendedor asignado no coincide.')
      assert.equal(saved.priceListId ?? null, lists.get(folded(customer.priceList))?.id ?? null, 'La lista asignada no coincide.')
      assert.equal(saved.zone ?? null, zones.get(folded(customer.zone))?.name ?? null, 'La zona asignada no coincide.')
    }
    const firstCustomer = data.customers[0]?.businessName
    await show(firstCustomer ? `/customers?search=${encodeURIComponent(firstCustomer)}` : '/customers', 'Clientes', firstCustomer)
    await record('06-clientes', { clientes: data.customers.length }, async () => {
      const form = await blank('+ Nuevo cliente')
      await expect(form.getByLabel('Lista de precios predeterminada')).toBeEnabled()
      return form
    })

    if (options.sample) {
      const customer = savedCustomers.get(folded(data.customers[0].businessName))
      const product = savedProducts.get(folded(firstProductName))
      const order = await api('POST', '/api/orders/confirm', { idempotencyKey: randomUUID(), customerId: customer.id,
        lines: [{ productId: product.id, quantity: 1, lineDiscountPercent: 0 }], orderDiscountPercent: 0 })
      assert(order.total > 0, 'El pedido de prueba debe tener importe.')
      await api('POST', `/api/orders/${order.orderId}/prepare`)
      await api('POST', `/api/orders/${order.orderId}/delivery-attempts`, { result: 'DELIVERED', payments: [{ method: 'CASH', amount: order.total }] })
      const detail = await api('GET', `/api/orders/${order.orderId}`)
      assert.equal(detail.order.status, 'DELIVERED')
      assert.equal(detail.payments.reduce((sum, payment) => sum + payment.amount, 0), order.total)
      const after = (await all('/api/products?includeStock=true')).find((item) => item.id === product.id)
      assert.equal(after.stock, product.stock - 1)
      await show(`/orders/${order.orderId}`, customer.name, 'Pagos registrados')
      await record('07-validacion', { pedidoEntregado: true, cobrado: order.total, stockFinal: after.stock }, async () => {
        await show('/orders/new', 'Nuevo pedido')
        return page.getByRole('main')
      })
    } else {
      await show('/orders', 'Pedidos')
      await record('07-validacion', { pedidos: 0, ventas: 0, pruebaComercial: 'disponible en Sample' }, async () => {
        await show('/orders/new', 'Nuevo pedido')
        return page.getByRole('main')
      })
    }
  } finally {
    if (refreshToken) {
      const logout = await page.request.post(new URL('/api/auth/logout', url).href, { data: { refreshToken } }).catch(() => null)
      await logout?.dispose()
    }
    await browser.close()
  }
}

try {
  if (process.argv[2] === '--validate') await readData(process.argv[3])
  else if (process.argv[2] === '--check-browser') await (await chromium.launch(browserOptions)).close()
  else await run(JSON.parse(await text(process.stdin)))
} catch (error) {
  process.stderr.write(`${error.message}\n`)
  process.exitCode = 1
}
