import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderCreatePage from './OrderCreatePage'
import { selectEntity } from '../../test/selectEntity'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const customers = { content: [
  { id: 'customer-1', name: 'Almacén Norte', priceListId: 'list-1', sellerId: 'seller-1', seller: 'Lucía', balance: 0 },
  { id: 'customer-2', name: 'Almacén Sur', priceListId: 'list-1', sellerId: 'seller-1', seller: 'Lucía', balance: 0 },
], page: 0, size: 20, totalElements: 2, totalPages: 1 }
const sellers = { content: [
  { id: 'seller-1', displayName: 'Lucía', email: 'lucia@example.test' },
  { id: 'seller-2', displayName: 'Martín', email: 'martin@example.test' },
], page: 0, size: 100, totalElements: 2, totalPages: 1 }
const products = { content: [{ id: 'product-1', name: 'Harina', presentation: 'Bolsa', status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
const lists = { content: [{ id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

function renderPage(authorities = ['ORDER_CREATE']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/orders/new']}><Routes><Route path="/orders/new" element={<OrderCreatePage />} /><Route path="/orders" element={<h1>Listado de pedidos</h1>} /></Routes></MemoryRouter></QueryClientProvider>)
  return queryClient
}

function catalogResponse(input: RequestInfo | URL) {
  const path = String(input)
  if (path.endsWith('/last-order')) return response({ available: false })
  if (path.startsWith('/api/customers')) return response(customers)
  if (path.startsWith('/api/sellers')) return response(sellers)
  if (path.startsWith('/api/products')) return response(products)
  if (path.startsWith('/api/pricing/lists')) return response(lists)
  if (path.startsWith('/api/pricing/resolve-batch')) return response(new URL(path, 'http://localhost').searchParams.get('productIds')!.split(',').map((productId) => ({ priceListId: 'list-1', priceListCode: 'MAYORISTA', productId, unitPrice: 150.5 })))
  return response({})
}

describe('OrderCreatePage', () => {
  it('blocks non-half-unit quantities in edited lines and confirms decimal-comma quantities', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/orders/confirm') return response({ orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-001', saleNumber: 'VEN-001', total: 225.75, paid: 0, balance: 225.75 }, 201)
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', 'Harina')
    await screen.findByDisplayValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    const quantity = screen.getByLabelText('Cantidad de Harina')
    const confirm = screen.getByRole('button', { name: 'Confirmar pedido' })
    for (const invalid of ['1.3', '1,3', '1.25', '0', '-0.5', 'abc', '1e3', '']) {
      fireEvent.change(quantity, { target: { value: invalid } })
      expect(quantity).toHaveAttribute('aria-invalid', 'true')
      expect(quantity).toHaveAccessibleDescription('La cantidad de Harina debe ser positiva y múltiplo de 0,5.')
      expect(confirm).toBeDisabled()
      expect(document.querySelector('.total-value')).toHaveTextContent('—')
      fireEvent.submit(confirm.closest('form')!)
      expect(fetchMock.mock.calls.some(([path]) => String(path) === '/api/orders/confirm')).toBe(false)
    }
    for (const valid of ['0.5', '1', '1,5']) {
      fireEvent.change(quantity, { target: { value: valid } })
      expect(quantity).toHaveAttribute('aria-invalid', 'false')
      expect(confirm).toBeEnabled()
      expect(screen.queryByText('La cantidad de Harina debe ser positiva y múltiplo de 0,5.')).not.toBeInTheDocument()
    }
    expect(document.querySelector('.total-value')).toHaveTextContent('225,75')
    await user.click(confirm)
    await screen.findByText('Pedido confirmado')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([path]) => String(path) === '/api/orders/confirm')?.[1]?.body))
    expect(request.lines).toEqual([{ productId: 'product-1', quantity: 1.5, lineDiscountPercent: 0 }])
  })

  it('requires correcting non-half-unit quantities loaded from a previous order', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).endsWith('/last-order')) return response({ available: true, items: [{ productId: 'product-1', productName: 'Harina', status: 'ACTIVE', quantity: 1.3, lineDiscountPercent: 0 }] })
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await user.click(await screen.findByRole('button', { name: 'Cargar pedido anterior' }))
    const quantity = await screen.findByLabelText('Cantidad de Harina')
    expect(quantity).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('button', { name: 'Confirmar pedido' })).toBeDisabled()
    fireEvent.change(quantity, { target: { value: '1.5' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmar pedido' })).toBeEnabled())
  })

  it('keeps the selected search after adding products and resets it when changing customers', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/products')) return response({ ...products, content: [
        { ...products.content[0], name: 'Producto uno' },
        { ...products.content[0], id: 'product-2', name: 'Producto dos' },
        { ...products.content[0], id: 'product-3', name: 'Arroz' },
      ] })
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    const input = screen.getByRole('combobox', { name: 'Producto' })
    await user.click(input)
    await user.type(input, 'Pro', { skipClick: true })
    await user.click(screen.getByRole('option', { name: /^Producto uno$/ }))
    await screen.findByDisplayValue('150,5')
    const requests = fetchMock.mock.calls.length
    const add = screen.getByRole('button', { name: 'Agregar producto' })
    await user.click(add)
    expect(input).toHaveValue('Pro')
    expect(add).toBeDisabled()
    await user.click(input)
    expect(input).toHaveValue('Pro')
    expect(screen.queryByRole('option', { name: /^Producto uno$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /^Arroz$/ })).not.toBeInTheDocument()
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    await user.click(add)
    expect(input).toHaveValue('Pro')
    expect(screen.getByLabelText('Cantidad de Producto uno')).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad de Producto dos')).toBeInTheDocument()
    expect(fetchMock.mock.calls).toHaveLength(requests)
    await user.click(input)
    await user.click(screen.getByRole('option', { name: 'Seleccionar producto...' }))
    expect(input).toHaveValue('Seleccionar producto...')
    await user.clear(input)
    await user.type(input, 'arr', { skipClick: true })
    await user.click(screen.getByRole('option', { name: /^Arroz$/ }))
    await user.click(add)
    expect(input).toHaveValue('arr')
    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.getByRole('combobox', { name: 'Producto' })).toHaveValue('Seleccionar producto...')
  })
  it('filters all product pages locally by name without accents and selects with the keyboard', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/products?page=0&size=100&includeStock=false') return response({ ...products, totalPages: 2 })
      if (String(input) === '/api/products?page=1&size=100&includeStock=false') return response({ ...products, page: 1, content: [{ ...products.content[0], id: 'product-2', name: 'Café molido' }, { ...products.content[0], id: 'inactive', name: 'Café inactivo', status: 'INACTIVE' }] })
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    const input = screen.getByRole('combobox', { name: 'Producto' })
    await user.click(input)
    expect(screen.getByRole('option', { name: /^Harina$/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /^Café molido$/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Café inactivo/ })).not.toBeInTheDocument()
    await user.clear(input)
    await user.type(input, 'cafe')
    expect(screen.queryByRole('option', { name: /^Harina$/ })).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: /^Café molido$/ })).toBeInTheDocument()
    await user.clear(input)
    await user.type(input, 'cafe')
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(input).toHaveValue('Café molido')
    await screen.findByDisplayValue('150,5')
    const requests = fetchMock.mock.calls.length
    await user.click(input)
    await user.clear(input)
    await user.type(input, 'no existe')
    expect(screen.getByText('No se encontraron coincidencias.')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(input).toHaveValue('Café molido')
    expect(fetchMock.mock.calls).toHaveLength(requests)
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getByLabelText('Cantidad de Café molido')).toBeInTheDocument()
    expect(input).toHaveValue('cafe')
    await user.click(input)
    expect(screen.queryByRole('option', { name: /^Café molido$/ })).not.toBeInTheDocument()
  })
  it('preloads the visible products once and reuses prices when selecting, adding, removing and refocusing', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/products')) return response({ ...products, content: [...products.content, { ...products.content[0], id: 'product-2', name: 'Arroz' }] })
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/pricing/resolve-batch'))).toHaveLength(1))
    await selectEntity(user, 'Producto', /^Arroz$/)
    await screen.findByDisplayValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    await selectEntity(user, 'Producto', /^Harina$/)
    expect(screen.getByLabelText('Precio')).toHaveValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Quitar Arroz' }))
    await selectEntity(user, 'Producto', /^Arroz$/)
    expect(screen.getByLabelText('Precio')).toHaveValue('150,5')
    window.dispatchEvent(new Event('focus'))
    expect(fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/pricing/resolve'))).toHaveLength(1)
    expect(String(fetchMock.mock.calls.find(([path]) => String(path).startsWith('/api/pricing/resolve-batch'))![0])).toContain('productIds=product-1,product-2')
  })

  it('blocks only products omitted from the batch because they lack a price', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/products')) return response({ ...products, content: [...products.content, { ...products.content[0], id: 'product-2', name: 'Arroz' }] })
      if (String(input).startsWith('/api/pricing/resolve-batch')) return response([{ productId: 'product-1', priceListId: 'list-1', priceListCode: 'GENERAL', unitPrice: 0 }])
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Arroz$/)
    await screen.findByText(/Este producto no tiene precio disponible/)
    expect(screen.getByRole('button', { name: 'Agregar producto' })).toBeDisabled()
    await selectEntity(user, 'Producto', /^Harina$/)
    expect(screen.getByLabelText('Precio')).toHaveValue('0')
    expect(screen.getByRole('button', { name: 'Agregar producto' })).toBeEnabled()
  })
  it('cancels an untouched draft without confirmation', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(catalogResponse)
    renderPage()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.getByText('Listado de pedidos')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('preserves a draft on dismissal and navigates only after confirming discard', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation(catalogResponse)
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    const cancel = screen.getByRole('button', { name: 'Cancelar' })
    const unloadBeforeCancel = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unloadBeforeCancel)
    expect(unloadBeforeCancel.defaultPrevented).toBe(true)
    await user.click(cancel)
    expect(screen.getByRole('button', { name: 'Seguir editando' })).toHaveFocus()
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
    expect(screen.getByLabelText('Cliente')).toHaveValue('Almacén Norte')
    expect(cancel).toHaveFocus()
    await user.click(cancel)
    await user.click(screen.getByRole('button', { name: 'Seguir editando' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await user.click(cancel)
    await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
    expect(screen.getByText('Listado de pedidos')).toBeInTheDocument()
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(false)
  })

  it('makes customers and sellers from later pages searchable', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/customers?page=0&size=20') return response({ ...customers, totalPages: 2 })
      if (path === '/api/customers?page=1&size=20') return response({ ...customers, content: [{ ...customers.content[0], id: 'late-customer', name: 'Cliente posterior', sellerId: 'late-seller' }], page: 1, totalPages: 2 })
      if (path === '/api/sellers?page=0&size=100') return response({ ...sellers, totalPages: 2 })
      if (path === '/api/sellers?page=1&size=100') return response({ ...sellers, content: [{ ...sellers.content[0], id: 'late-seller', displayName: 'Vendedor posterior' }], page: 1, totalPages: 2 })
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Cliente posterior')
    expect(screen.getByLabelText('Cliente')).toHaveValue('Cliente posterior')
    expect(screen.getByLabelText('Vendedor')).toHaveValue('Vendedor posterior')
    await selectEntity(user, 'Vendedor', 'Martín')
    expect(screen.getByLabelText('Vendedor')).toHaveValue('Martín')
  })
  const dialogMethods = ['showModal', 'close'] as const
  const originalDialogMethods = dialogMethods.map((method) => Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, method))
  afterEach(() => {
    cleanup(); sessionStorage.clear()
    dialogMethods.forEach((method, index) => {
      const descriptor = originalDialogMethods[index]
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, method, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, method)
    })
  })
  beforeEach(() => {
    vi.restoreAllMocks()
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } })
  })

  const previous = { available: true, orderId: 'old-order', orderNumber: 'PED-ANTERIOR', orderDiscountPercent: 5, items: [{ productId: 'product-2', productName: 'Arroz', presentation: 'Bolsa', status: 'ACTIVE', quantity: 3, lineDiscountPercent: 10 }] }

  it('includes partial previous balance as a separate undiscounted amount in confirmation', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/customers') && !String(input).endsWith('/last-order')) return response({ ...customers, content: [{ ...customers.content[0], balance: 1000 }] })
      if (String(input) === '/api/orders/confirm') return response({ orderId: 'new', saleId: 'sale', orderNumber: 'PED-SALDO', saleNumber: 'VEN-SALDO', total: 135.45, paid: 0, balance: 135.45, previousBalanceAmount: 250.5, collectionTotal: 385.95 })
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByDisplayValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    const amount = screen.getByLabelText('Importe para el remito')
    await user.type(amount, '250,5')
    await user.click(screen.getByRole('button', { name: 'Agregar importe' }))
    expect(screen.getByRole('button', { name: 'Quitar Saldo anterior' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad de Saldo anterior')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Descuento de Saldo anterior')).not.toBeInTheDocument()
    await user.clear(screen.getByLabelText('Descuento general (%)'))
    await user.type(screen.getByLabelText('Descuento general (%)'), '10')
    expect(document.querySelector('.total-value')).toHaveTextContent('385,95')
    await user.click(screen.getByRole('button', { name: 'Confirmar pedido' }))
    await screen.findByText('PED-SALDO')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([path]) => path === '/api/orders/confirm')?.[1]?.body))
    expect(request.previousBalanceAmount).toBe(250.5)
    expect(request.lines).toEqual([{ productId: 'product-1', quantity: 1, lineDiscountPercent: 0 }])
    expect(request.payments).toEqual([])
    expect(screen.getByText('Total a cobrar con la entrega')).toBeInTheDocument()
  })

  it('replaces the balance row when updating it and resets it when removing it or changing customers', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).startsWith('/api/customers') && !String(input).endsWith('/last-order') ? response({ ...customers, content: customers.content.map((customer) => ({ ...customer, balance: 1000 })) }) : catalogResponse(input))
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await user.click(screen.getByRole('button', { name: 'Agregar saldo total' }))
    expect(document.querySelector('.total-value')).toHaveTextContent('1.000,00')
    expect(screen.getByRole('button', { name: 'Confirmar pedido' })).toBeDisabled()
    const amount = screen.getByLabelText('Importe para el remito')
    await user.clear(amount)
    await user.type(amount, '200')
    await user.click(screen.getByRole('button', { name: 'Actualizar importe' }))
    expect(screen.getAllByRole('button', { name: 'Quitar Saldo anterior' })).toHaveLength(1)
    expect(document.querySelector('.total-value')).toHaveTextContent('200,00')
    await user.click(screen.getByRole('button', { name: 'Quitar Saldo anterior' }))
    expect(screen.queryByRole('button', { name: 'Quitar Saldo anterior' })).not.toBeInTheDocument()
    expect(document.querySelector('.total-value')).toHaveTextContent('0,00')
    await user.click(screen.getByRole('button', { name: 'Agregar saldo total' }))
    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.queryByRole('button', { name: 'Quitar Saldo anterior' })).not.toBeInTheDocument()
    expect(amount).toHaveValue('')
  })

  it('disables balance inclusion without debt and rejects amounts exceeding the current balance', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).startsWith('/api/customers') && !String(input).endsWith('/last-order') ? response({ ...customers, content: [{ ...customers.content[0], balance: 50 }, { ...customers.content[1], balance: -20 }] }) : catalogResponse(input))
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.getByLabelText('Importe para el remito')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Agregar saldo total' })).toBeDisabled()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    const input = screen.getByLabelText('Importe para el remito')
    for (const value of ['-1', '51', '0', '1,12345']) {
      await user.clear(input)
      await user.type(input, value)
      await user.click(screen.getByRole('button', { name: 'Agregar importe' }))
      expect(screen.getByRole('alert')).toHaveTextContent('Ingresá un importe mayor a cero y hasta')
      expect(screen.queryByRole('button', { name: 'Quitar Saldo anterior' })).not.toBeInTheDocument()
    }
  })

  it('disables previous-order loading until the customer has an available order', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage()
    const button = await screen.findByRole('button', { name: 'Cargar pedido anterior' })
    expect(button).toBeDisabled()
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/last-order'))).toBe(false)
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await waitFor(() => expect(button).toHaveAttribute('title', 'Este cliente no tiene pedidos anteriores.'))
    expect(button).toBeDisabled()
  })

  it('replaces the draft with previous quantities including off-page products and uses current prices', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).endsWith('/last-order')) return response(previous)
      if (String(input) === '/api/orders/confirm') return response({ orderId: 'new-order', saleId: 'sale-1', orderNumber: 'PED-NUEVO', saleNumber: 'VEN-NUEVA', total: 386.03, paid: 0, balance: 386.03 })
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByDisplayValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    await user.click(screen.getByRole('button', { name: 'Cargar pedido anterior' }))
    expect(screen.getByRole('dialog', { name: 'El pedido anterior tiene descuentos' })).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad de Harina')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Copiar descuentos' }))
    expect(screen.queryByLabelText('Cantidad de Harina')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad de Arroz')).toHaveValue('3')
    expect(screen.getByLabelText('Descuento de Arroz')).toHaveValue('10')
    expect(screen.getByLabelText('Descuento general (%)')).toHaveValue('5')
    await waitFor(() => expect(document.querySelector('.total-value')).toHaveTextContent('386,03'))
    await user.click(screen.getByRole('button', { name: 'Confirmar pedido' }))
    await screen.findByText('PED-NUEVO')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([input]) => input === '/api/orders/confirm')?.[1]?.body))
    expect(request.lines).toEqual([{ productId: 'product-2', quantity: 3, lineDiscountPercent: 10 }])
    expect(request.orderDiscountPercent).toBe(5)
    expect(request.payments).toEqual([])
    expect(request.priceListId).toBe('list-1')
  })

  it('copies quantities without discounts for non-admins', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).endsWith('/last-order') ? response(previous) : catalogResponse(input))
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Cargar pedido anterior' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad de Arroz')).toHaveValue('3')
    expect(screen.queryByLabelText('Descuento de Arroz')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Descuento general (%)')).not.toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('.total-value')).toHaveTextContent('451,50'))
  })

  it('lets admins load without discounts or cancel without changing the draft', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).endsWith('/last-order') ? response(previous) : catalogResponse(input))
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    const button = screen.getByRole('button', { name: 'Cargar pedido anterior' })
    await waitFor(() => expect(button).toBeEnabled())
    await user.click(button)
    expect(screen.getByText('Descuento general: 5%')).toBeInTheDocument()
    expect(screen.getByText('Arroz: 10%')).toBeInTheDocument()
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad de Arroz')).not.toBeInTheDocument()
    expect(button).toHaveFocus()
    await user.click(button)
    await user.click(screen.getByRole('button', { name: 'Cargar sin descuentos' }))
    expect(screen.getByLabelText('Cantidad de Arroz')).toHaveValue('3')
    expect(screen.getByLabelText('Descuento de Arroz')).toHaveValue('0')
    expect(screen.getByLabelText('Descuento general (%)')).toHaveValue('0')
    await waitFor(() => expect(document.querySelector('.total-value')).toHaveTextContent('451,50'))
  })

  it('loads a previous order without a prompt when it has no discounts', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).endsWith('/last-order') ? response({ ...previous, orderDiscountPercent: 0, items: [{ ...previous.items[0], lineDiscountPercent: 0 }] }) : catalogResponse(input))
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Cargar pedido anterior' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Descuento de Arroz')).toHaveValue('0')
  })

  it('blocks unavailable products without replacing the current draft', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).endsWith('/last-order') ? response({ ...previous, items: [{ ...previous.items[0], status: 'INACTIVE' }] }) : catalogResponse(input))
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByDisplayValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    await user.click(screen.getByRole('button', { name: 'Cargar pedido anterior' }))
    expect(screen.getByRole('alert')).toHaveTextContent('productos no disponibles (Arroz)')
    expect(screen.getByLabelText('Cantidad de Harina')).toHaveValue('1')
    expect(screen.queryByLabelText('Cantidad de Arroz')).not.toBeInTheDocument()
  })

  it('retries previous-order failures and discards history when switching customers', async () => {
    const user = userEvent.setup()
    let attempts = 0
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/customers/customer-1/last-order') return ++attempts === 1 ? response({}, 500) : response(previous)
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await screen.findByText('No se pudo consultar el pedido anterior.')
    expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Reintentar pedido anterior' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toBeEnabled())
    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toBeDisabled()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toHaveAttribute('title', 'Este cliente no tiene pedidos anteriores.'))
    expect(screen.queryByLabelText('Cantidad de Arroz')).not.toBeInTheDocument()
  })

  it('loads admin sellers and places Seller between Customer and Price List in the approved grid', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])

    await selectEntity(user, 'Cliente', 'Almacén Norte')

    const customerField = screen.getByLabelText('Cliente')
    const sellerField = screen.getByLabelText('Vendedor')
    const priceListField = screen.getByLabelText('Lista de precios')
    expect(sellerField).toHaveValue('Lucía')
    expect(priceListField).toHaveValue('list-1')
    expect(customerField.compareDocumentPosition(sellerField) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(sellerField.compareDocumentPosition(priceListField) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(customerField.closest('.order-customer-grid')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/sellers?page=0&size=100', expect.anything())
  })

  it('shows a non-admin the selected customer seller without requesting sellers', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ORDER_CREATE'])

    await selectEntity(user, 'Cliente', 'Almacén Norte')

    expect(screen.getByText('Lucía')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Vendedor' })).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/sellers?page=0&size=100', expect.anything())
  })

  it('retries a failed admin seller-list read and then shows the order form', async () => {
    const user = userEvent.setup()
    let sellerFetches = 0
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/sellers')) {
        sellerFetches += 1
        if (sellerFetches === 1) return response({}, 503)
      }
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])

    expect(await screen.findByText('No se pudo preparar el pedido')).toBeInTheDocument()
    expect(screen.queryByLabelText('Cliente')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar vendedores' }))

    expect(await screen.findByLabelText('Cliente')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(5)
    expect(sellerFetches).toBe(2)
  })

  it('allows an admin to override the seller and resets it when the customer changes', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])

    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Vendedor', 'Martín')
    expect(screen.getByLabelText('Vendedor')).toHaveValue('Martín')

    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.getByLabelText('Vendedor')).toHaveValue('Lucía')
  })

  it('validates the general discount inline and accepts a decimal comma', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/orders/confirm') return response({ orderId: 'new-order', saleId: 'sale-1', orderNumber: 'PED-DESCUENTO', saleNumber: 'VEN-1', total: 134.7, paid: 0, balance: 134.7 })
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByDisplayValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    const input = screen.getByLabelText('Descuento general (%)')
    for (const value of ['-1', '101', 'texto']) {
      await user.clear(input)
      await user.type(input, value)
      expect(input).toHaveAttribute('aria-invalid', 'true')
      expect(screen.getByRole('alert')).toHaveTextContent('Ingresá un descuento entre 0 y 100%.')
      expect(screen.getByRole('button', { name: 'Confirmar pedido' })).toBeDisabled()
    }
    await user.clear(input)
    await user.type(input, '10,5')
    expect(input).toHaveAttribute('aria-invalid', 'false')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(document.querySelector('.total-value')).toHaveTextContent('134,70')
    await user.click(screen.getByRole('button', { name: 'Confirmar pedido' }))
    await screen.findByText('PED-DESCUENTO')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([path]) => path === '/api/orders/confirm')?.[1]?.body))
    expect(request.orderDiscountPercent).toBe(10.5)
  })

  it('confirms an order using resolved list prices and a stable idempotency key', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/orders/confirm' && init?.method === 'POST') return response({
        orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-001', saleNumber: 'VEN-001', total: 301, paid: 0, balance: 301,
      }, 201)
      return catalogResponse(input)
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    expect(screen.queryByText('Elegí un cliente, revisá los precios de su lista y confirmá el pedido.')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Depósito para el pedido')).not.toBeInTheDocument()

    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await user.click(screen.getByRole('button', { name: /agregar producto/i }))
    await screen.findAllByText(/150,50/)
    await user.clear(screen.getByLabelText('Cantidad de Harina'))
    await user.type(screen.getByLabelText('Cantidad de Harina'), '2')
    expect(screen.queryByRole('heading', { name: 'Cobro' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Medio de pago')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Importe de pago')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /confirmar pedido/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/confirm', expect.objectContaining({
      method: 'POST',
      body: expect.stringMatching(/"idempotencyKey":"[^"]+"/),
    })))
    const request = JSON.parse(String(fetchMock.mock.calls.find(([input]) => input === '/api/orders/confirm')?.[1]?.body))
    expect(request).toMatchObject({
      customerId: 'customer-1', priceListId: 'list-1', orderDiscountPercent: 0,
      lines: [{ productId: 'product-1', quantity: 2, lineDiscountPercent: 0 }],
      payments: [],
    })
    expect(request).not.toHaveProperty('depotId')
    expect(request).not.toHaveProperty('sellerId')
    expect(fetchMock).not.toHaveBeenCalledWith('/api/inventory/depots', expect.anything())
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
    expect(await screen.findByText('PED-001')).toBeInTheDocument()
    expect(screen.getByText('VEN-001')).toBeInTheDocument()
    expect(screen.getByText(/saldo pendiente/i)).toBeInTheDocument()
  })

  it('retries a failed confirmation with the exact same idempotency key and payload', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const sentBodies: string[] = []
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/orders/confirm' && init?.method === 'POST') {
        attempts += 1
        sentBodies.push(String(init.body))
        if (attempts === 1) return Promise.reject(new Error('Conexión interrumpida'))
        return response({ orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-002', saleNumber: 'VEN-002', total: 150.5, paid: 0, balance: 150.5 }, 201)
      }
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])

    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Vendedor', 'Martín')
    await selectEntity(user, 'Producto', /^Harina$/)
    await user.click(screen.getByRole('button', { name: /agregar producto/i }))
    await user.click(screen.getByRole('button', { name: /confirmar pedido/i }))
    await screen.findByText('Conexión interrumpida')
    await user.click(screen.getByRole('button', { name: /reintentar confirmación/i }))

    await screen.findByText('PED-002')
    await waitFor(() => expect(attempts).toBe(2))
    expect(attempts).toBe(2)
    expect(sentBodies[1]).toBe(sentBodies[0])
    expect(JSON.parse(sentBodies[0])).toHaveProperty('sellerId', 'seller-2')
    expect(JSON.parse(sentBodies[0])).not.toHaveProperty('depotId')
    expect(fetchMock).not.toHaveBeenCalledWith('/api/inventory/depots', expect.anything())
  })

  it('shows price discounts only to admins', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ORDER_CREATE'])

    await screen.findByLabelText('Cliente')
    expect(screen.queryByLabelText('Descuento general (%)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Descuento')).not.toBeInTheDocument()
  })

  it('allows admins to override the loaded price for this order without updating a price list', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/orders/confirm') return response({ orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-004', saleNumber: 'VEN-004', total: 123.5, paid: 0, balance: 123.5 }, 201)
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    const price = await screen.findByDisplayValue('150,5')
    expect(price).not.toHaveAttribute('readonly')
    expect(screen.queryByLabelText('Precio manual para el producto')).not.toBeInTheDocument()
    await user.clear(price)
    await user.type(price, '123,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getAllByText(/123,50/)).toHaveLength(4)
    await user.click(screen.getByRole('button', { name: 'Confirmar pedido' }))
    await screen.findByText('PED-004')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([input]) => input === '/api/orders/confirm')?.[1]?.body))
    expect(request.lines).toEqual([{ productId: 'product-1', quantity: 1, lineDiscountPercent: 0, unitPriceOverride: 123.5 }])
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT' || init?.method === 'PATCH')).toHaveLength(0)
  })

  it('keeps the price read-only for non-admins and omits price overrides from confirmation', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/orders/confirm') return response({ orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-005', saleNumber: 'VEN-005', total: 150.5, paid: 0, balance: 150.5 }, 201)
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    const price = await screen.findByDisplayValue('150,5')
    expect(price).toHaveAttribute('readonly')
    await user.type(price, '999')
    expect(price).toHaveValue('150,5')
    expect(screen.queryByLabelText('Descuento')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar pedido' }))
    await screen.findByText('PED-005')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([input]) => input === '/api/orders/confirm')?.[1]?.body))
    expect(request.lines[0]).not.toHaveProperty('unitPriceOverride')
    expect(request.lines[0].lineDiscountPercent).toBe(0)
  })

  it('restores the list price when the customer, list or selected product changes', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/pricing/lists')) return response({ ...lists, content: [...lists.content, { id: 'list-2', code: 'MINORISTA', name: 'Minorista', status: 'ACTIVE' }] })
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByDisplayValue('150,5')
    const changePrice = async () => {
      await user.clear(screen.getByLabelText('Precio'))
      await user.type(screen.getByLabelText('Precio'), '999')
    }
    await changePrice()
    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.getByLabelText('Precio')).toHaveValue('150,5')
    await changePrice()
    await user.selectOptions(screen.getByLabelText('Lista de precios'), 'list-2')
    await waitFor(() => expect(screen.getByLabelText('Precio')).toHaveValue('150,5'))
    await changePrice()
    await selectEntity(user, 'Producto', 'Seleccionar producto...')
    await selectEntity(user, 'Producto', /^Harina$/)
    await waitFor(() => expect(screen.getByLabelText('Precio')).toHaveValue('150,5'))
  })

  it.each(['0', '100'])('accepts the discount boundary %s and a zero price override', async (discount) => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    const price = await screen.findByDisplayValue('150,5')
    await user.clear(price)
    await user.type(price, '0')
    await user.clear(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'))
    await user.type(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'), discount)
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getByLabelText('Descuento de Harina')).toHaveValue(discount)
    expect(screen.getAllByText(/0,00/).length).toBeGreaterThan(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('selects each customer assigned list and resets a manual override when switching customers', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/customers')) return response({ ...customers, content: [customers.content[0], { ...customers.content[1], priceListId: 'list-2' }] })
      if (String(input).startsWith('/api/pricing/lists')) return response({ ...lists, content: [...lists.content, { id: 'list-2', code: 'MINORISTA', name: 'Minorista', status: 'ACTIVE' }] })
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.getByLabelText('Lista de precios')).toHaveValue('list-2')
    await user.selectOptions(screen.getByLabelText('Lista de precios'), 'list-1')
    expect(screen.getByLabelText('Lista de precios')).toHaveValue('list-1')
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Cliente', 'Almacén Sur')
    expect(screen.getByLabelText('Lista de precios')).toHaveValue('list-2')
  })

  it('uses GENERAL automatically for a customer without an assigned list', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/customers')) return response({ ...customers, content: [{ ...customers.content[0], priceListId: null }] })
      if (String(input).startsWith('/api/pricing/lists')) return response({ ...lists, content: [...lists.content, { id: 'general', code: 'GENERAL', name: 'General', status: 'ACTIVE' }] })
      return catalogResponse(input)
    })
    renderPage()
    const customer = await screen.findByLabelText('Cliente')
    expect(screen.getByLabelText('Lista de precios')).toHaveValue('')
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    expect(screen.getByLabelText('Lista de precios')).toHaveValue('general')
    expect(screen.getByLabelText('Producto')).toBeEnabled()
  })

  it('loads later list pages so the customer assigned list is visible and its prices are used', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response({ ...lists, content: [{ id: 'general', code: 'GENERAL', name: 'General', status: 'ACTIVE' }], totalPages: 2 })
      if (path === '/api/pricing/lists?page=1&size=20') return response({ ...lists, page: 1, totalPages: 2 })
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    expect(screen.getByLabelText('Lista de precios')).toHaveValue('list-1')
    expect(screen.getByRole('option', { name: 'MAYORISTA · Mayorista' })).toBeInTheDocument()
    await selectEntity(user, 'Producto', /^Harina$/)
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    await screen.findAllByText(/150,50/)
    expect(fetchMock).toHaveBeenCalledWith('/api/pricing/resolve-batch?customerId=customer-1&priceListId=list-1&productIds=product-1', expect.anything())
  })

  it('shows an unavailable assigned list instead of silently selecting another one', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/pricing/lists')) return response({ ...lists, content: [{ ...lists.content[0], status: 'INACTIVE' }, { id: 'general', code: 'GENERAL', name: 'General', status: 'ACTIVE' }] })
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    expect(screen.getByLabelText('Lista de precios')).toHaveValue('list-1')
    expect(screen.getByText('Seleccioná una lista activa para continuar.')).toBeInTheDocument()
    expect(screen.getByLabelText('Producto')).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Lista de precios'), 'general')
    expect(screen.getByLabelText('Producto')).toBeEnabled()
  })

  it('shows the list price before adding and carries quantity and product discount into confirmation', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/orders/confirm') return response({ orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-003', saleNumber: 'VEN-003', total: 338.625, paid: 0, balance: 338.625 }, 201)
      return catalogResponse(input)
    })
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    expect(await screen.findByDisplayValue('150,5')).toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad de Harina')).not.toBeInTheDocument()
    await user.clear(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Cantidad'))
    await user.type(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Cantidad'), '2,5')
    await user.clear(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'))
    await user.type(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'), '10')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getByLabelText('Cantidad de Harina')).toHaveValue('2.5')
    expect(screen.getByLabelText('Descuento de Harina')).toHaveValue('10')
    expect(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Cantidad')).toHaveValue('1')
    expect(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento')).toHaveValue('0')
    expect(screen.getAllByText(/338,63/)).toHaveLength(3)
    await user.click(screen.getByRole('button', { name: 'Confirmar pedido' }))
    await screen.findByText('PED-003')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([input]) => input === '/api/orders/confirm')?.[1]?.body))
    expect(request.lines).toEqual([{ productId: 'product-1', quantity: 2.5, lineDiscountPercent: 10 }])
  })

  it('removes a product with the trash button and recalculates the total', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByDisplayValue('150,5')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    const removeButton = screen.getByRole('button', { name: 'Quitar Harina' })
    expect(removeButton.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    await user.click(removeButton)
    expect(screen.queryByLabelText('Cantidad de Harina')).not.toBeInTheDocument()
    expect(within(screen.getByRole('table')).getByText('Elegí un producto para consultar su precio en la lista seleccionada.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmar pedido' })).toBeDisabled()
    expect(document.querySelector('.total-value')).toHaveTextContent('0,00')
    await user.click(screen.getByLabelText('Producto')); expect(screen.getByRole('option', { name: /^Harina$/ })).toBeInTheDocument()
  })

  it('waits for the selected list price and ignores a delayed response for the previous list', async () => {
    const user = userEvent.setup()
    let resolveOldPrice!: (value: Response) => void
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path.startsWith('/api/pricing/lists')) return response({ ...lists, content: [...lists.content, { id: 'list-2', code: 'MINORISTA', name: 'Minorista', status: 'ACTIVE' }] })
      if (path.startsWith('/api/pricing/resolve')) {
        if (path.includes('priceListId=list-1')) return new Promise<Response>((resolve) => { resolveOldPrice = resolve })
        return response([{ priceListId: 'list-2', priceListCode: 'MINORISTA', productId: 'product-1', unitPrice: 290 }])
      }
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    expect(screen.getByRole('button', { name: 'Agregar producto' })).toBeDisabled()
    expect(screen.getByLabelText('Precio')).toHaveAttribute('placeholder', 'Consultando...')
    await user.selectOptions(screen.getByLabelText('Lista de precios'), 'list-2')
    await screen.findByDisplayValue('290')
    resolveOldPrice(await response([{ priceListId: 'list-1', priceListCode: 'MAYORISTA', productId: 'product-1', unitPrice: 450 }]))
    await waitFor(() => expect(screen.getByLabelText('Precio')).toHaveValue('290'))
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getAllByText(/290,00/)).toHaveLength(4)
  })

  it('blocks adding a product with an unavailable price and allows retrying the price lookup', async () => {
    const user = userEvent.setup()
    let priceAttempts = 0
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/pricing/resolve') && ++priceAttempts === 1) return response({}, 404)
      return catalogResponse(input)
    })
    renderPage()
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByText(/No se pudo consultar el precio de la lista/)
    expect(screen.getByRole('button', { name: 'Agregar producto' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Reintentar precio' }))
    await screen.findByDisplayValue('150,5')
    expect(screen.getByRole('button', { name: 'Agregar producto' })).toBeEnabled()
  })

  it('validates quantity and product discount before adding the line', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ADMIN_ALL', 'ORDER_CREATE'])
    await selectEntity(user, 'Cliente', 'Almacén Norte')
    await selectEntity(user, 'Producto', /^Harina$/)
    await screen.findByDisplayValue('150,5')
    await user.clear(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Cantidad'))
    await user.type(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Cantidad'), '1.25')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getByRole('alert')).toHaveTextContent('La cantidad debe ser positiva y múltiplo de 0,5.')
    expect(screen.queryByLabelText('Cantidad de Harina')).not.toBeInTheDocument()
    await user.clear(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Cantidad'))
    await user.type(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Cantidad'), '2')
    await user.clear(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'))
    await user.type(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'), '-1')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getByRole('alert')).toHaveTextContent('El descuento debe estar entre 0 y 100%')
    expect(screen.queryByLabelText('Cantidad de Harina')).not.toBeInTheDocument()
    await user.clear(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'))
    await user.type(within(screen.getByLabelText('Producto').closest('.product-picker') as HTMLElement).getByLabelText('Descuento'), '101')
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getByRole('alert')).toHaveTextContent('El descuento debe estar entre 0 y 100%')
    expect(screen.queryByLabelText('Cantidad de Harina')).not.toBeInTheDocument()
  })
})
