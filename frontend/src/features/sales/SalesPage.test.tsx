import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SalesPage from './SalesPage'

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

describe('SalesPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', 'access-token'))

  it('shows sale totals, paid balance and status from the API', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({
      content: [{ id: 'sale-1', number: 'VEN-001', customer: 'Almacén Norte', total: 300, paid: 100, balance: 200, status: 'DELIVERED', date: '2026-09-24T10:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    }))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><MemoryRouter><SalesPage /></MemoryRouter></QueryClientProvider>)

    expect(await screen.findByText('VEN-001')).toBeInTheDocument()
    expect(screen.getByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.getByText('Entregada')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/sales?page=0&size=20&search=', expect.anything())
  })
})
