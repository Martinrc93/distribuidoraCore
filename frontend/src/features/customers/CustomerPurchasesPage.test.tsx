import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CustomerPurchasesPage from './CustomerPurchasesPage'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

const report = {
  summary: { customerId: 'cust-1', customer: 'Almacén Norte', dateMin: '2026-10-01', dateMax: '2026-10-31', salesCount: 1, totalAmount: 420 },
  purchases: {
    content: [{
      id: 'sale-1', orderId: 'ord-1', saleNumber: 'VEN-001', orderNumber: 'PED-001',
      date: '2026-10-15T14:00:00Z', status: 'DELIVERED', amount: 420,
      items: [{ id: 'item-1', product: 'Harina', soldUnits: 4, returnedUnits: 1, netUnits: 3, amount: 420 }],
    }],
    page: 0, size: 20, totalElements: 1, totalPages: 1,
  },
}

function renderPage(entry = '/customers/cust-1/purchases?dateMin=01%2F10%2F2026&dateMax=31%2F10%2F2026') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}>
    <Routes><Route path="/customers/:customerId/purchases" element={<CustomerPurchasesPage />} /></Routes>
  </MemoryRouter></QueryClientProvider>)
}

describe('CustomerPurchasesPage', () => {
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL', 'CUSTOMER_VIEW'])))
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('loads purchase history, expands item details and links back to the customer', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response(report))
    renderPage()

    expect(await screen.findByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.getAllByText(/420,00/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByRole('link', { name: 'Volver a la ficha' })).toHaveAttribute('href', '/customers/cust-1')
    await userEvent.setup().click(screen.getByText('Pedido PED-001'))
    expect(screen.getByText('Harina')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver pedido PED-001' })).toHaveAttribute('href', '/orders/ord-1')
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/reports/customers/cust-1/purchases?dateMin=2026-10-01&dateMax=2026-10-31'), expect.any(Object))
  })

  it('validates inverted date ranges before fetching and retries transient failures', async () => {
    const fetchMock = vi.spyOn(global, 'fetch')
      .mockImplementationOnce(() => response({ detail: 'Falla temporal' }, 500))
      .mockImplementation(() => response(report))
    renderPage()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reintentar' }))
    await screen.findByText('Almacén Norte')

    fetchMock.mockClear()
    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '10/10/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    fetchMock.mockClear()
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '20/10/2026' } })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('La fecha inicial no puede ser posterior'))
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
