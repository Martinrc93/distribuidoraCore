import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AttentionNow } from './AttentionNow'
import { analyticsReport as report } from '../../test/fixtures/analyticsDashboard'

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))

function renderAttention() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter><AttentionNow /></MemoryRouter></QueryClientProvider>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('AttentionNow', () => {
  it('shows pending reviews, deliveries and critical stock with links to act on them', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).startsWith('/api/orders')
      ? json({ content: [], totalElements: 2, totalPages: 1, number: 0, size: 5 })
      : json({ ...report, current: { ...report.current, pendingOrders: 12, oldestPendingAt: '2026-10-26T13:00:00Z', stockAlertCount: 3 } }))
    renderAttention()
    const section = screen.getByRole('region', { name: 'Para atender ahora' })
    const deliveries = (await within(section).findByRole('heading', { name: 'Pedidos por entregar' })).closest('article')!
    expect(await within(deliveries).findByText('12')).toBeInTheDocument()
    expect(within(deliveries).getByText('El más antiguo es del 26/10/2026.')).toBeInTheDocument()
    expect(within(deliveries).getByRole('link', { name: 'Ver pedidos por entregar' })).toHaveAttribute('href', '/orders?status=CONFIRMED&dateMin=&dateMax=')
    const reviews = within(section).getByRole('heading', { name: 'Pedidos de vendedores por revisar' }).closest('article')!
    expect(within(reviews).getByText('2')).toBeInTheDocument()
    expect(within(reviews).getByRole('link', { name: 'Ver revisiones' })).toHaveAttribute('href', '/orders?review=PENDING&dateMin=&dateMax=')
    const stock = within(section).getByRole('heading', { name: 'Stock crítico' }).closest('article')!
    expect(within(stock).getByText('3')).toBeInTheDocument()
  })

  it('keeps the other cards when the current situation cannot be read, and lets it retry', async () => {
    let analyticsCalls = 0
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input).startsWith('/api/orders')) return json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 })
      analyticsCalls += 1
      return analyticsCalls === 1 ? json({ outdated: true }) : json(report)
    })
    renderAttention()
    expect(await screen.findByText('Todo revisado.')).toBeInTheDocument()
    const deliveries = screen.getByRole('heading', { name: 'Pedidos por entregar' }).closest('article')!
    expect(await within(deliveries).findByText(/No se pudo consultar/)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fireEvent.click(within(deliveries).getByRole('button', { name: 'Volver a consultar' }))
    expect(await within(deliveries).findByText(String(report.current.pendingOrders))).toBeInTheDocument()
  })
})
