import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AnalyticsDashboardPage, { comparison } from './AnalyticsDashboardPage'
import { analyticsReport as report } from '../../test/fixtures/analyticsDashboard'
import { presetRange, rangeError, todayInArgentina } from './dashboardDates'

function renderDashboard(entry = '/analytics?dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}><AnalyticsDashboardPage /></MemoryRouter></QueryClientProvider>)
}
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('Analytics dashboard', () => {
  it('shows period metrics separately from current priorities and links to their details', async () => {
    const fetch = vi.spyOn(global, 'fetch').mockImplementation(() => Promise.resolve(new Response(JSON.stringify(report), { status: 200 })))
    renderDashboard()
    expect(await screen.findByText('Ventas del período')).toBeInTheDocument()
    expect(screen.getAllByText(/\+50% respecto del período anterior/)).toHaveLength(2)
    expect(screen.getByText('Deuda pendiente actual')).toBeInTheDocument()
    expect(screen.getByText('Stock negativo')).toBeInTheDocument()
    expect(screen.getByText('Sin ventas')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Ventas diarias/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver deuda de Cliente principal' })).toHaveAttribute('href', '/sales?customerId=customer-1&pendingBalance=true')
    expect(screen.getByRole('link', { name: 'Abrir pedido ORD-001' })).toHaveAttribute('href', '/orders/order-1')
    expect(fetch).toHaveBeenCalledWith('/api/dashboard/analytics?dateMin=2026-09-01&dateMax=2026-09-30', expect.any(Object))
  })
  it('refetches when selecting a period and on explicit refresh', async () => {
    const fetch = vi.spyOn(global, 'fetch').mockImplementation(() => Promise.resolve(new Response(JSON.stringify(report), { status: 200 })))
    const user = userEvent.setup()
    renderDashboard()
    await screen.findByText('Ventas del período')
    await user.selectOptions(screen.getByLabelText('Período'), 'today')
    const today = todayInArgentina()
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(`/api/dashboard/analytics?dateMin=${today}&dateMax=${today}`, expect.any(Object)))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Actualizar datos' })).toBeEnabled())
    const before = fetch.mock.calls.length
    await user.click(screen.getByRole('button', { name: 'Actualizar datos' }))
    await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(before))
  })
  it('does not query an invalid date range', () => {
    const fetch = vi.spyOn(global, 'fetch')
    renderDashboard('/analytics?dateMin=30%2F09%2F2026&dateMax=01%2F09%2F2026')
    expect(screen.getByRole('alert')).toHaveTextContent('La fecha inicial no puede ser posterior a la final.')
    expect(fetch).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Actualizar datos' })).toBeDisabled()
  })
  it('offers a working retry after an API error', async () => {
    const fetch = vi.spyOn(global, 'fetch').mockImplementationOnce(() => Promise.resolve(new Response('{}', { status: 500 })))
      .mockImplementation(() => Promise.resolve(new Response(JSON.stringify(report), { status: 200 })))
    const user = userEvent.setup()
    renderDashboard()
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Ventas del período')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('shows useful empty states and never invents a percentage without a baseline', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ ...report, trend: [], topDebtors: [], stockAlerts: [], stockCoverage: [], topProducts: [], sellers: [], pendingOrders: [], recentOrders: [] }), { status: 200 })))
    renderDashboard()
    expect(await screen.findByText('No hay ventas registradas en este período.')).toBeInTheDocument()
    expect(screen.getByText('No hay pedidos pendientes de entrega.')).toBeInTheDocument()
    expect(comparison(1, 0)).toBe('Sin base de comparación')
    expect(comparison(0, 0)).toBe('Sin variación')
  })
  it.each([null, {}, { ...report, sales: null }, { ...report, recentOrders: [{ ...report.pendingOrders[0], date: 'invalid' }] }])('handles an incompatible report without crashing the page', async (body) => {
    vi.spyOn(global, 'fetch').mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify(body), { status: 200 })))
      .mockImplementation(() => Promise.resolve(new Response(JSON.stringify(report), { status: 200 })))
    const user = userEvent.setup()
    renderDashboard()
    expect(await screen.findByRole('alert')).toHaveTextContent('La respuesta del servidor no es compatible')
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(screen.queryByText('Ventas del período')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Ventas del período')).toBeInTheDocument()
  })
})
describe('Business date ranges', () => {
  it('uses Argentina midnight and Monday as the start of the week', () => {
    expect(todayInArgentina(new Date('2026-10-02T01:00:00Z'))).toBe('2026-10-01')
    expect(presetRange('week', '2026-10-04')).toEqual({ start: '2026-09-28', end: '2026-10-04' })
    expect(rangeError('2025-01-01', '2026-10-02', '2026-10-02')).toContain('366')
    expect(rangeError('2026-10-02', '2026-10-03', '2026-10-02')).toContain('futuras')
  })
})
