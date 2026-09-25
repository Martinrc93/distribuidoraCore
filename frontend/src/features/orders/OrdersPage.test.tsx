import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrdersPage from './OrdersPage'

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

describe('OrdersPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', 'access-token'))

  it('lists real orders and opens their detail route', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({
      content: [{ id: 'order-1', number: 'PED-001', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-24T10:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    }))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/orders']}><Routes>
      <Route path="/orders" element={<OrdersPage />} />
      <Route path="/orders/:orderId" element={<h1>Detalle solicitado</h1>} />
    </Routes></MemoryRouter></QueryClientProvider>)

    expect(await screen.findByText('PED-001')).toBeInTheDocument()
    expect(screen.getByText('Almacén Norte')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=&status=', expect.anything())
    await screen.findByRole('link', { name: /abrir pedido PED-001/i })
  })
})
