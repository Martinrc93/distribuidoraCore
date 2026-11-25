import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SoldProductsPage from './SoldProductsPage'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

function renderPage(entry: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}>
    <Routes>
      <Route path="/sales/products" element={<SoldProductsPage />} />
      <Route path="/sales/products/:productId" element={<SoldProductsPage />} />
    </Routes>
  </MemoryRouter></QueryClientProvider>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('SoldProductsPage', () => {
  it('renders product totals, validates date range and displays list-filter notice from sales returnTo', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({
      content: [{ productId: 'prod-1', product: 'Harina', salesCount: 2, soldUnits: 5, returnedUnits: 1, netUnits: 4, amount: 400 }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    }))
    const returnTo = encodeURIComponent('/sales?customerId=cust-1&pendingBalance=true')
    renderPage(`/sales/products?dateMin=01%2F10%2F2026&dateMax=31%2F10%2F2026&returnTo=${returnTo}`)

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('no aplica los filtros de búsqueda, cliente, vendedor o saldo del listado anterior')
    expect(screen.getByRole('link', { name: 'Volver a ventas' })).toHaveAttribute('href', '/sales?customerId=cust-1&pendingBalance=true')
    expect(screen.getByRole('link', { name: 'Ver ventas de Harina' })).toHaveAttribute('href', expect.stringContaining('/sales/products/prod-1?'))

    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '10/10/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    fetchMock.mockClear()
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '20/10/2026' } })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('La fecha inicial no puede ser posterior'))
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('renders product drilldown history with clickable sale number and returnTo analytics label', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const url = String(input)
      if (url.includes('/sales?')) {
        return response({
          product: { productId: 'prod-1', product: 'Harina', salesCount: 1, soldUnits: 3, returnedUnits: 0, netUnits: 3, amount: 300 },
          sales: {
            content: [{ id: 'sale-1', orderId: 'order-1', number: 'VEN-010', customer: 'Cliente 1', status: 'DELIVERED', date: '2026-10-10T12:00:00Z', soldUnits: 3, returnedUnits: 0, netUnits: 3, amount: 300 }],
            page: 0, size: 20, totalElements: 1, totalPages: 1,
          },
        })
      }
      return response({})
    })

    renderPage('/sales/products/prod-1?dateMin=01%2F10%2F2026&dateMax=31%2F10%2F2026&returnTo=%2Fanalytics%3Fperiod%3Dmonth')

    expect(await screen.findByRole('heading', { name: 'Harina' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Abrir pedido de la venta VEN-010' })).toHaveAttribute('href', '/orders/order-1')
    expect(screen.getByRole('link', { name: 'Ver pedido de la venta VEN-010' })).toHaveAttribute('href', '/orders/order-1')
    expect(screen.getByRole('link', { name: 'Volver a artículos vendidos' })).toHaveAttribute('href', expect.stringContaining('/sales/products?'))
  })
})
