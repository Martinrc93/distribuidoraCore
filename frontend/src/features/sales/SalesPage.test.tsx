import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SalesPage from './SalesPage'

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }

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

  it('loads real sale line IDs and submits an authorized return', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/sales?page=')) return response({
        content: [{ id: 'sale-1', number: 'VEN-001', customer: 'Almacén Norte', total: 300, paid: 100, balance: 200, status: 'DELIVERED', date: '2026-09-24T10:00:00Z' }],
        page: 0, size: 20, totalElements: 1, totalPages: 1,
      })
      if (path === '/api/sales/sale-1' && !init?.method) return response({
        sale: { id: 'sale-1', number: 'VEN-001', status: 'DELIVERED' },
        saleItems: [{ saleItemId: 'line-1', productId: 'product-1', productName: 'Harina', quantity: 2, returnedQuantity: 0, returnableQuantity: 2 }],
      })
      if (path === '/api/sales/sale-1/returns' && init?.method === 'POST') return response({ returnId: 'return-1' }, 201)
      return response({})
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    render(<QueryClientProvider client={queryClient}><MemoryRouter><SalesPage /></MemoryRouter></QueryClientProvider>)

    await user.click(await screen.findByRole('button', { name: 'Ver venta' }))
    expect(await screen.findByText('Harina')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Motivo de la devolución'), 'Producto dañado')
    await user.type(screen.getByLabelText('Cantidad a devolver · Harina'), '1')
    await user.click(screen.getByRole('button', { name: 'Registrar devolución' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/sales/sale-1/returns', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ reason: 'Producto dañado', items: [{ saleItemId: 'line-1', quantity: 1 }] }),
    })))
    expect(await screen.findByRole('status')).toHaveTextContent(/stock actualizado/i)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sale-detail', 'sale-1'] })
  })
})
