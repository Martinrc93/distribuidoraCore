import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardPage, { type DashboardData } from './DashboardPage'

const data: DashboardData = {
  dateMin: '2026-09-30', dateMax: '2026-09-30',
  totals: { performedOrders: 5, deliveredOrders: 2, totalBilled: 380.12, totalPaid: 100.02, cashPaid: 80, transferPaid: 20.02, accountBalance: 280.10 },
  bySeller: [
    { sellerId: 'seller-1', seller: 'Lucía', performedOrders: 3, deliveredOrders: 1, totalBilled: 300.12, totalPaid: 40.02, cashPaid: 20, transferPaid: 20.02, accountBalance: 260.10 },
    { sellerId: null, seller: 'Sin asignar', performedOrders: 2, deliveredOrders: 1, totalBilled: 80, totalPaid: 60, cashPaid: 60, transferPaid: 0, accountBalance: 20 },
  ],
}

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

function renderDashboard(entry = '/dashboard') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}><DashboardPage /></MemoryRouter></QueryClientProvider>)
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-01T02:00:00Z'))
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers() })

  it('defaults to the Argentine day and shows totals and all seller rows', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response(data))
    renderDashboard()
    await screen.findByText('Lucía')
    expect(screen.getByLabelText('Desde')).toHaveValue('30/09/2026')
    expect(screen.getByLabelText('Hasta')).toHaveValue('30/09/2026')
    expect(fetchMock).toHaveBeenCalledWith('/api/dashboard?dateMin=2026-09-30&dateMax=2026-09-30', expect.anything())
    const totals = screen.getByRole('region', { name: 'Totales del período' })
    expect(within(totals).getByText('5')).toBeInTheDocument()
    expect(within(totals).getByText('2')).toBeInTheDocument()
    expect(within(totals).getByText(/380,12/)).toBeInTheDocument()
    expect(within(totals).getByText(/100,02/)).toBeInTheDocument()
    expect(within(totals).getByText(/280,10/)).toBeInTheDocument()
    expect(screen.getByText('Sin asignar')).toBeInTheDocument()
    const row = screen.getByText('Lucía').closest('tr')!
    expect(within(row).getByText(/300,12/)).toBeInTheDocument()
    expect(within(row).getByText(/40,02/)).toBeInTheDocument()
    expect(within(row).getByText(/20,00/)).toBeInTheDocument()
    expect(within(row).getByText(/20,02/)).toBeInTheDocument()
    expect(within(row).getByText(/260,10/)).toBeInTheDocument()
  })

  it('queries an explicit range, updates dates and restores today', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response(data))
    renderDashboard('/dashboard?dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026')
    await screen.findByText('Lucía')
    expect(fetchMock).toHaveBeenCalledWith('/api/dashboard?dateMin=2026-09-01&dateMax=2026-09-30', expect.anything())
    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '01/10/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/dashboard?dateMin=2026-09-01&dateMax=2026-10-01', expect.anything()))
    fireEvent.click(screen.getByRole('button', { name: 'Hoy' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/dashboard?dateMin=2026-09-30&dateMax=2026-09-30', expect.anything()))
    expect(screen.getByLabelText('Desde')).toHaveValue('30/09/2026')
  })

  it.each(['31/09/2026', '01/10/2026', ''])('blocks invalid or reversed ranges: %s', async (value) => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response(data))
    renderDashboard()
    await screen.findByText('Lucía')
    fetchMock.mockClear()
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value } })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Totales del período' })).not.toBeInTheDocument()
    expect(screen.queryByText('Lucía')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows zero totals and an empty table for a period without orders', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response({ ...data, totals: { performedOrders: 0, deliveredOrders: 0, totalBilled: 0, totalPaid: 0, cashPaid: 0, transferPaid: 0, accountBalance: 0 }, bySeller: [] }))
    renderDashboard()
    expect(await screen.findByText('No hay pedidos en el período seleccionado.')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Totales del período' })).not.toHaveTextContent('NaN')
  })

  it('keeps the date controls available and retries a failed request', async () => {
    vi.spyOn(global, 'fetch').mockRejectedValueOnce(new Error('Error de conexión')).mockImplementation(() => response(data))
    renderDashboard()
    await screen.findByText('No se pudo cargar el resumen')
    expect(screen.getByLabelText('Desde')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Lucía')).toBeInTheDocument()
  })

  it('keeps the page usable when an outdated backend returns the old summary format', async () => {
    vi.spyOn(global, 'fetch').mockImplementationOnce(() => response({ todaySales: 100, pendingBalance: 50 }))
      .mockImplementation(() => response(data))
    renderDashboard()
    expect(await screen.findByText(/La respuesta del servidor no es compatible/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Resumen operativo' })).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Totales del período' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Lucía')).toBeInTheDocument()
  })
})
