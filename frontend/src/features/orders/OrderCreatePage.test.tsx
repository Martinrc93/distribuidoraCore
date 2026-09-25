import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderCreatePage from './OrderCreatePage'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const customers = { content: [{ id: 'customer-1', name: 'Almacén Norte', priceListId: 'list-1', sellerId: 'seller-1', balance: 0 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
const products = { content: [{ id: 'product-1', sku: 'SKU-1', name: 'Harina', presentation: 'Bolsa', stock: 12, status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
const lists = { content: [{ id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

function renderPage(authorities = ['ORDER_CREATE']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><OrderCreatePage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

function catalogResponse(input: RequestInfo | URL) {
  const path = String(input)
  if (path.startsWith('/api/customers')) return response(customers)
  if (path.startsWith('/api/products')) return response(products)
  if (path.startsWith('/api/pricing/lists')) return response(lists)
  if (path.startsWith('/api/pricing/resolve')) return response({ priceListId: 'list-1', priceListCode: 'MAYORISTA', productId: 'product-1', unitPrice: 150.5 })
  return response({})
}

describe('OrderCreatePage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => { vi.restoreAllMocks() })

  it('confirms an order using resolved list prices and a stable idempotency key', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/orders/confirm' && init?.method === 'POST') return response({
        orderId: 'order-1', saleId: 'sale-1', orderNumber: 'PED-001', saleNumber: 'VEN-001', total: 301, paid: 100, balance: 201,
      }, 201)
      return catalogResponse(input)
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    expect(screen.queryByText('Elegí un cliente, revisá los precios de su lista y confirmá el pedido.')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Depósito para el pedido')).not.toBeInTheDocument()

    await user.selectOptions(await screen.findByLabelText('Cliente'), 'customer-1')
    await user.selectOptions(screen.getByLabelText('Lista de precios'), 'list-1')
    await user.selectOptions(screen.getByLabelText('Producto'), 'product-1')
    await user.click(screen.getByRole('button', { name: /agregar producto/i }))
    await screen.findAllByText(/150,50/)
    await user.clear(screen.getByLabelText('Cantidad de Harina'))
    await user.type(screen.getByLabelText('Cantidad de Harina'), '2')
    await user.selectOptions(screen.getByLabelText('Medio de pago'), 'CASH')
    await user.type(screen.getByLabelText('Importe de pago'), '100')
    await user.click(screen.getByRole('button', { name: /confirmar pedido/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/confirm', expect.objectContaining({
      method: 'POST',
      body: expect.stringMatching(/"idempotencyKey":"[^"]+"/),
    })))
    const request = JSON.parse(String(fetchMock.mock.calls.find(([input]) => input === '/api/orders/confirm')?.[1]?.body))
    expect(request).toMatchObject({
      customerId: 'customer-1', priceListId: 'list-1', orderDiscountPercent: 0,
      lines: [{ productId: 'product-1', quantity: 2, lineDiscountPercent: 0 }],
      payments: [{ method: 'CASH', amount: 100 }],
    })
    expect(request).not.toHaveProperty('depotId')
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

    await user.selectOptions(await screen.findByLabelText('Cliente'), 'customer-1')
    await user.selectOptions(screen.getByLabelText('Lista de precios'), 'list-1')
    await user.selectOptions(screen.getByLabelText('Producto'), 'product-1')
    await user.click(screen.getByRole('button', { name: /agregar producto/i }))
    await user.click(screen.getByRole('button', { name: /confirmar pedido/i }))
    await screen.findByText('Conexión interrumpida')
    await user.click(screen.getByRole('button', { name: /reintentar confirmación/i }))

    await screen.findByText('PED-002')
    await waitFor(() => expect(attempts).toBe(2))
    expect(attempts).toBe(2)
    expect(sentBodies[1]).toBe(sentBodies[0])
    expect(JSON.parse(sentBodies[0])).not.toHaveProperty('depotId')
    expect(fetchMock).not.toHaveBeenCalledWith('/api/inventory/depots', expect.anything())
  })

  it('shows price discounts only to admins', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ORDER_CREATE'])

    await screen.findByLabelText('Cliente')
    expect(screen.queryByLabelText('Descuento general (%)')).not.toBeInTheDocument()
  })
})
