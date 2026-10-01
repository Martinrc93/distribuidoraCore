import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderDetailPage from './OrderDetailPage'
import { selectEntity } from '../../test/selectEntity'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const detail = {
  order: { id: 'order-1', number: 'PED-001', customerId: 'customer-1', customer: 'Almacén Norte', seller: 'Lucía', status: 'CONFIRMED', subtotal: 300, discount: 0, total: 300, customerBalance: 300, date: '2026-09-24T10:00:00Z' },
  items: [{ productId: 'product-1', productName: 'Harina', quantity: 2, unitPrice: 150, lineTotal: 300, priceListId: 'list-1', priceListCode: 'MAYORISTA', lineDiscountPercent: 0 }],
  sale: { id: 'sale-1', number: 'VEN-001', status: 'CONFIRMED', total: 300, paid: 100, balance: 200, date: '2026-09-24T10:00:00Z' },
  payments: [{ id: 'payment-1', amount: 100, method: 'CASH', transferReference: null, date: '2026-09-24T10:00:00Z' }],
  account: { debit: 300, credit: 100, net: 200 },
}

function renderPage(authorities = ['ADMIN_ALL'], entry = '/orders/order-1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[entry]}><Routes><Route path="/orders/:orderId" element={<OrderDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>)
  return queryClient
}

function deliveryAttempt(result: 'FAILED' | 'DELIVERED', attemptNumber = 1) {
  return { id: `attempt-${attemptNumber}`, attemptNumber, result, observation: result === 'FAILED' ? 'Cliente ausente' : null, attemptedAt: '2026-10-01T15:00:00Z', attemptedBy: 'user-1' }
}

function catalogResponse(input: RequestInfo | URL) {
  const path = String(input)
  const paged = (content: unknown[]) => response({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 })
  if (path.startsWith('/api/products')) return paged([{ id: 'product-1', name: 'Harina', status: 'ACTIVE' }, { id: 'product-2', name: 'Arroz', status: 'ACTIVE' }])
  if (path.startsWith('/api/pricing/lists')) return paged([{ id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }, { id: 'list-2', code: 'GENERAL', name: 'General', status: 'ACTIVE' }])
  if (path.startsWith('/api/pricing/resolve-batch')) return response([{ productId: 'product-1', unitPrice: 200 }, { productId: 'product-2', unitPrice: 50 }])
  if (path.endsWith('/last-order')) return response({ available: false })
  return response({})
}

describe('OrderDetailPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => { vi.restoreAllMocks() })

  it('opens the editing form directly from its URL for an admin', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input) === '/api/orders/order-1' ? response(detail) : catalogResponse(input))
    renderPage(['ADMIN_ALL'], '/orders/order-1?edit=true')
    await screen.findByRole('heading', { name: 'Editar pedido' })
    expect(await screen.findByRole('combobox', { name: 'Cliente' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeInTheDocument()
  })

  it.each([
    { name: 'a non-admin', authorities: ['ORDER_CREATE'], status: 'CONFIRMED' },
    { name: 'an admin viewing a delivered order', authorities: ['ADMIN_ALL'], status: 'DELIVERED' },
    { name: 'an admin viewing a cancelled order', authorities: ['ADMIN_ALL'], status: 'CANCELLED' },
  ])('does not open editing from the URL for $name', async ({ authorities, status }) => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response({ ...detail, order: { ...detail.order, status } }))
    renderPage(authorities, '/orders/order-1?edit=true')
    await screen.findByRole('heading', { name: 'Productos del pedido' })
    expect(screen.queryByRole('heading', { name: 'Editar pedido' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Guardar cambios' })).not.toBeInTheDocument()
  })

  it.each([
    { name: 'missing history', deliveryAttempts: undefined },
    { name: 'no attempts', deliveryAttempts: [] },
    { name: 'only successful attempts', deliveryAttempts: [deliveryAttempt('DELIVERED')] },
  ])('hides delivery attempts with $name', async ({ deliveryAttempts }) => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response({ ...detail, deliveryAttempts }))
    renderPage(['ORDER_CREATE'])

    await screen.findByRole('heading', { name: 'PED-001' })
    expect(screen.queryByRole('heading', { name: 'Intentos de entrega' })).not.toBeInTheDocument()
    expect(screen.queryByText('Todavía no hay intentos')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Productos del pedido' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Pagos registrados' })).toBeInTheDocument()
  })

  it.each([
    { name: 'a failed attempt', deliveryAttempts: [deliveryAttempt('FAILED')] },
    { name: 'a successful retry after failure', deliveryAttempts: [deliveryAttempt('FAILED'), deliveryAttempt('DELIVERED', 2)] },
  ])('shows the complete delivery history with $name', async ({ deliveryAttempts }) => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response({ ...detail, deliveryAttempts }))
    renderPage(['ORDER_CREATE'])

    const heading = await screen.findByRole('heading', { name: 'Intentos de entrega' })
    const panel = within(heading.closest('section')!)
    expect(panel.getAllByRole('row')).toHaveLength(deliveryAttempts.length + 1)
    expect(panel.getByText('No entregada')).toBeInTheDocument()
    expect(panel.getByText('Cliente ausente')).toBeInTheDocument()
    if (deliveryAttempts.length > 1) expect(panel.getByText('Entregada')).toBeInTheDocument()
  })

  it('renders order and sale snapshots, payments and account ledger totals', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response(detail))
    renderPage(['ORDER_CREATE'])

    expect(await screen.findByRole('heading', { name: 'PED-001' })).toBeInTheDocument()
    expect(screen.getByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.getByText('Harina')).toBeInTheDocument()
    expect(screen.getByText(/VEN-001/)).toBeInTheDocument()
    expect(screen.getByText('MAYORISTA')).toBeInTheDocument()
    expect(screen.getByText('Efectivo')).toBeInTheDocument()
    expect(screen.getByText('Saldo total del cliente')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /editar pedido/i })).not.toBeInTheDocument()
  })

  it('edits a confirmed order only for admins and sends line snapshots to the edit endpoint', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/orders/order-1' && !init?.method) return response(detail)
      if (String(input) === '/api/orders/order-1' && init?.method === 'PUT') return response({ orderId: 'order-1', saleId: 'sale-1', total: 450, paid: 100, balance: 350 })
      return catalogResponse(input)
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /editar pedido/i }))
    await user.clear(screen.getByLabelText('Cantidad de Harina'))
    await user.type(screen.getByLabelText('Cantidad de Harina'), '3')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ priceListId: 'list-1', lines: [{ productId: 'product-1', quantity: 3, lineDiscountPercent: 0, unitPriceOverride: 150 }], orderDiscountPercent: 0, previousBalanceAmount: 0 }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/orders/order-1'] })
  })

  it('uses the creation form with locked customer and seller and preserves saved prices and discounts', async () => {
    const user = userEvent.setup()
    const discounted = { ...detail, order: { ...detail.order, discount: 43.5, orderDiscountPercent: 5 }, items: [{ ...detail.items[0], lineDiscountPercent: 10 }] }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => String(input) === '/api/orders/order-1' ? response(discounted) : catalogResponse(input))
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Editar pedido' }))

    await screen.findByRole('heading', { name: 'Datos del pedido' })
    expect(screen.getByRole('combobox', { name: 'Cliente' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Cliente' })).toHaveValue('Almacén Norte')
    expect(screen.getByRole('combobox', { name: 'Vendedor' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Vendedor' })).toHaveValue('Lucía')
    expect(screen.getByLabelText('Precio unitario de Harina')).toHaveValue('150')
    expect(screen.getByLabelText('Descuento de Harina')).toHaveValue('10')
    expect(screen.getByLabelText('Descuento general (%)')).toHaveValue('5')
    expect(document.querySelector('.total-value')).toHaveTextContent('256,50')
    expect(fetchMock.mock.calls.some(([path]) => String(path).startsWith('/api/customers?') || String(path).startsWith('/api/sellers'))).toBe(false)
    expect(screen.getByRole('button', { name: 'Agregar producto' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Quitar Harina' })).toBeInTheDocument()
  })

  it('adds and removes products, edits prices and remittance amounts, and updates the existing order only', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/orders/order-1') return init?.method === 'PUT' ? response({}) : response(detail)
      return catalogResponse(input)
    })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Editar pedido' }))
    await screen.findByLabelText('Cantidad de Harina')
    await user.click(screen.getByRole('button', { name: 'Quitar Harina' }))
    await selectEntity(user, 'Producto', 'Arroz')
    await screen.findByDisplayValue('50')
    fireEvent.change(screen.getByLabelText('Cantidad', { exact: true }), { target: { value: '1,5' } })
    await user.click(screen.getByRole('button', { name: 'Agregar producto' }))
    fireEvent.change(screen.getByLabelText('Precio unitario de Arroz'), { target: { value: '80,5' } })
    fireEvent.change(screen.getByLabelText('Descuento de Arroz'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Importe para el remito'), { target: { value: '40' } })
    await user.click(screen.getByRole('button', { name: 'Agregar importe' }))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await screen.findByText('Pedido actualizado. Se conservaron los cobros existentes.')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([path, init]) => String(path) === '/api/orders/order-1' && init?.method === 'PUT')?.[1]?.body))
    expect(request).toEqual({ priceListId: 'list-1', lines: [{ productId: 'product-2', quantity: 1.5, lineDiscountPercent: 10, unitPriceOverride: 80.5 }], orderDiscountPercent: 0, previousBalanceAmount: 40 })
    expect(fetchMock.mock.calls.some(([path]) => String(path) === '/api/orders/confirm')).toBe(false)
  })

  it('validates half-unit quantities and retains edits on a save conflict', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/orders/order-1') return init?.method === 'PUT' ? response({ detail: 'El nuevo total no puede ser menor que el importe ya pagado' }, 409) : response(detail)
      return catalogResponse(input)
    })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Editar pedido' }))
    const quantity = await screen.findByLabelText('Cantidad de Harina')
    fireEvent.change(quantity, { target: { value: '1.3' } })
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled()
    expect(quantity).toHaveAttribute('aria-invalid', 'true')
    fireEvent.change(quantity, { target: { value: '1,5' } })
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El nuevo total no puede ser menor que el importe ya pagado')
    expect(quantity).toHaveValue('1,5')
    expect(screen.getByRole('combobox', { name: 'Cliente' })).toBeDisabled()
  })

  it('resolves the chosen list again when changing it instead of retaining saved price overrides', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/orders/order-1') return init?.method === 'PUT' ? response({}) : response(detail)
      return catalogResponse(input)
    })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Editar pedido' }))
    await screen.findByLabelText('Precio unitario de Harina')
    await user.selectOptions(screen.getByLabelText('Lista de precios'), 'list-2')
    await waitFor(() => expect(screen.getByLabelText('Precio unitario de Harina')).toHaveValue('200'))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await screen.findByText('Pedido actualizado. Se conservaron los cobros existentes.')
    const request = JSON.parse(String(fetchMock.mock.calls.find(([path, init]) => String(path) === '/api/orders/order-1' && init?.method === 'PUT')?.[1]?.body))
    expect(request.priceListId).toBe('list-2')
    expect(request.lines).toEqual([{ productId: 'product-1', quantity: 2, lineDiscountPercent: 0 }])
  })

  it('cancels unchanged edits immediately and confirms discarding modified edits', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input) === '/api/orders/order-1' ? response(detail) : catalogResponse(input))
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Editar pedido' }))
    await screen.findByLabelText('Cantidad de Harina')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Editar pedido' }))
    fireEvent.change(await screen.findByLabelText('Cantidad de Harina'), { target: { value: '3' } })
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await screen.findByRole('dialog', { name: '¿Descartar los cambios?' })
    await user.click(screen.getByRole('button', { name: 'Seguir editando' }))
    expect(screen.getByLabelText('Cantidad de Harina')).toHaveValue('3')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
    expect(screen.getByRole('heading', { name: 'Productos del pedido' })).toBeInTheDocument()
  })
})
