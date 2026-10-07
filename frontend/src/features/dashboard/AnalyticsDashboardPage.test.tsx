import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
  it('orders major debtors by balance and shows their share of all current debt', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      ...report,
      current: { ...report.current, debt: 1000, debtorCount: 8 },
      topDebtors: [
        { id: 'small', name: 'Cliente menor', balance: 100 },
        { id: 'large', name: 'Cliente mayor', balance: 500 },
        { id: 'medium', name: 'Cliente medio', balance: 200 },
      ],
    }), { status: 200 })))
    renderDashboard()
    const panel = (await screen.findByRole('heading', { name: 'Clientes con mayor deuda' })).closest('section')!
    const rows = within(panel).getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getAllByRole('cell')[0].textContent)).toEqual(['Cliente mayor', 'Cliente medio', 'Cliente menor'])
    expect(rows.map((row) => within(row).getAllByRole('cell')[2].textContent)).toEqual(['50%', '20%', '10%'])
    expect(within(panel).getByRole('status')).toHaveTextContent('Estos 3 clientes concentran $ 800,00, el 80% de la deuda total.')
    expect(within(panel).getByRole('link', { name: 'Ver todos los deudores' })).toHaveAttribute('href', '/customers?hasBalance=true&status=ALL')
  })
  it('keeps a clear empty state when there is no debt', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      ...report, current: { ...report.current, debt: 0, debtorCount: 0 }, topDebtors: [],
    }), { status: 200 })))
    renderDashboard()
    const panel = (await screen.findByRole('heading', { name: 'Clientes con mayor deuda' })).closest('section')!
    expect(within(panel).getByText('No hay clientes con deuda pendiente.')).toBeInTheDocument()
    expect(within(panel).queryByRole('status')).not.toBeInTheDocument()
    expect(panel.textContent).not.toMatch(/NaN|Infinity/)
  })
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
    expect(screen.queryByRole('link', { name: 'Ver pagos y deuda' })).not.toBeInTheDocument()
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
