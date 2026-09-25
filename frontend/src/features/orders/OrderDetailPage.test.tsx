import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderDetailPage from './OrderDetailPage'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const detail = {
  order: { id: 'order-1', number: 'PED-001', customerId: 'customer-1', customer: 'Almacén Norte', status: 'CONFIRMED', subtotal: 300, discount: 0, total: 300, customerBalance: 300, date: '2026-09-24T10:00:00Z' },
  items: [{ productId: 'product-1', productName: 'Harina', quantity: 2, unitPrice: 150, lineTotal: 300, priceListId: 'list-1', priceListCode: 'MAYORISTA', lineDiscountPercent: 0 }],
  sale: { id: 'sale-1', number: 'VEN-001', status: 'CONFIRMED', total: 300, paid: 100, balance: 200, date: '2026-09-24T10:00:00Z' },
  payments: [{ id: 'payment-1', amount: 100, method: 'CASH', transferReference: null, date: '2026-09-24T10:00:00Z' }],
  account: { debit: 300, credit: 100, net: 200 },
}

function renderPage(authorities = ['ADMIN_ALL']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/orders/order-1']}><Routes><Route path="/orders/:orderId" element={<OrderDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('OrderDetailPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => { vi.restoreAllMocks() })

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
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /editar pedido/i }))
    await user.clear(screen.getByLabelText('Cantidad de Harina'))
    await user.type(screen.getByLabelText('Cantidad de Harina'), '3')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ priceListId: 'list-1', lines: [{ productId: 'product-1', quantity: 3, lineDiscountPercent: 0 }], orderDiscountPercent: 0 }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/orders/order-1'] })
  })
})
