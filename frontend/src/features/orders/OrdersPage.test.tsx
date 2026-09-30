import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrdersPage from './OrdersPage'

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

function renderOrders(entry = '/orders') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[entry]}><Routes>
    <Route path="/orders" element={<OrdersPage />} />
    <Route path="/orders/:orderId" element={<h1>Detalle solicitado</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>)
}

function mockOrders() {
  return vi.spyOn(global, 'fetch').mockImplementation(() => response({
    content: [{ id: 'order-1', number: 'PED-001', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-30T02:00:00Z' }],
    page: 0, size: 20, totalElements: 21, totalPages: 2,
  }))
}

describe('OrdersPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks(); vi.useRealTimers() })
  beforeEach(() => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-token')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-30T12:00:00Z'))
  })

  it('lists real orders and opens their detail route', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({
      content: [{ id: 'order-1', number: 'PED-001', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-24T10:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    }))
    renderOrders()

    expect(await screen.findByText('PED-001')).toBeInTheDocument()
    expect(screen.getByText('Almacén Norte')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=&status=&dateMin=2026-09-30&dateMax=2026-09-30', expect.anything())
    expect(screen.getByLabelText('Fecha mín.')).toHaveValue('30/09/2026')
    expect(screen.getByLabelText('Fecha máx.')).toHaveValue('30/09/2026')
    expect(screen.getByText('24/09/2026')).toBeInTheDocument()
    await screen.findByRole('link', { name: /abrir pedido PED-001/i })
  })

  it('uses the Argentine calendar day for defaults and displayed dates', async () => {
    vi.setSystemTime(new Date('2026-09-30T02:00:00Z'))
    const fetchMock = mockOrders()
    renderOrders()
    expect(await screen.findByText('29/09/2026')).toBeInTheDocument()
    expect(screen.getByLabelText('Fecha mín.')).toHaveValue('29/09/2026')
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=2026-09-29&dateMax=2026-09-29'), expect.anything())
  })

  it('restores date filters from the URL and keeps them while paginating', async () => {
    const fetchMock = mockOrders()
    renderOrders('/orders?dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026&status=CONFIRMED&search=Norte')
    await screen.findByText('PED-001')
    expect(screen.getByLabelText('Fecha mín.')).toHaveValue('01/09/2026')
    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=1&size=20&search=Norte&status=CONFIRMED&dateMin=2026-09-01&dateMax=2026-09-30', expect.anything()))
    fireEvent.change(screen.getByLabelText('Fecha mín.'), { target: { value: '15/09/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=Norte&status=CONFIRMED&dateMin=2026-09-15&dateMax=2026-09-30', expect.anything()))
    fireEvent.change(screen.getByLabelText('Fecha máx.'), { target: { value: '25/09/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=2026-09-15&dateMax=2026-09-25'), expect.anything()))
  })

  it('allows clearing either boundary and both dates to show all dates', async () => {
    const fetchMock = mockOrders()
    renderOrders()
    await screen.findByText('PED-001')
    fireEvent.change(screen.getByLabelText('Fecha mín.'), { target: { value: '' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=&dateMax=2026-09-30'), expect.anything()))
    fireEvent.change(screen.getByLabelText('Fecha máx.'), { target: { value: '' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=&dateMax='), expect.anything()))
    fireEvent.change(screen.getByLabelText('Buscar pedidos'), { target: { value: 'Norte' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=Norte&status=&dateMin=&dateMax=', expect.anything()))
    expect(screen.getByLabelText('Fecha mín.')).toHaveValue('')
  })

  it.each(['31/09/2026', '29/02/2026', '30/9/2026', '2026-09-30', '30/09/0000', 'texto'])('rejects invalid date %s without fetching orders', async (value) => {
    const fetchMock = mockOrders()
    renderOrders()
    await screen.findByText('PED-001')
    fetchMock.mockClear()
    fireEvent.change(screen.getByLabelText('Fecha mín.'), { target: { value } })
    expect(screen.getByRole('alert')).toHaveTextContent('Las fechas deben ser válidas y tener formato dd/mm/aaaa.')
    expect(screen.getByLabelText('Fecha mín.')).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.queryByText('PED-001')).not.toBeInTheDocument()
  })

  it('rejects reversed ranges and accepts leap days', async () => {
    const fetchMock = mockOrders()
    renderOrders('/orders?dateMin=01%2F10%2F2026&dateMax=30%2F09%2F2026')
    expect(screen.getByRole('alert')).toHaveTextContent('La fecha mínima no puede ser posterior a la fecha máxima.')
    expect(fetchMock).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Fecha mín.'), { target: { value: '29/02/2024' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=2024-02-29&dateMax=2026-09-30'), expect.anything()))
  })
})
