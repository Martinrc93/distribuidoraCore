import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CommissionSettlementsPage from './CommissionSettlementsPage'
import { selectEntity } from '../../test/selectEntity'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

const page = (content: unknown[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 })

function renderSettlements(entry = '/commission-settlements?type=seller') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}><CommissionSettlementsPage /></MemoryRouter></QueryClientProvider>)
}

describe('CommissionSettlementsPage', () => {
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`))
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('loads seller statement, validates inverted date ranges and settles pending balance', async () => {
    const user = userEvent.setup()
    let settled = false
    const statement = () => ({
      name: 'Lucía Vendedora',
      pending: { token: 'tok-seller-1', entryCount: settled ? 0 : 1, amount: settled ? '0' : '250.5000' },
      periodAmount: '250.5000',
      movements: {
        content: [{
          id: 'mov-1', orderId: 'ord-1', orderNumber: 'PED-009', customer: 'Almacén Norte',
          product: 'Harina', returned: false, amount: '250.5000', occurredAt: '2026-10-12T12:00:00Z',
          paidAt: settled ? '2026-10-13T12:00:00Z' : null,
        }],
        page: 0, size: 20, totalElements: 1, totalPages: 1,
      },
    })

    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/sellers?')) return response(page([{ id: 'seller-1', displayName: 'Lucía Vendedora', status: 'ACTIVE' }]))
      if (path === '/api/commissions/sellers/seller-1/settlements' && init?.method === 'POST') {
        settled = true
        return response({ id: 'settle-1', amount: '250.5000', entryCount: 1, paidAt: '2026-10-13T12:00:00Z' }, 201)
      }
      if (path.startsWith('/api/commissions/sellers/seller-1?')) return response(statement())
      return response(page([]))
    })

    renderSettlements()
    await selectEntity(user, 'Vendedor', 'Lucía Vendedora')
    expect(await screen.findByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'PED-009' })).toHaveAttribute('href', '/orders/ord-1')

    await user.click(screen.getByRole('button', { name: 'Registrar pago al vendedor' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Registrar pago' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/commissions/sellers/seller-1/settlements', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ token: 'tok-seller-1' }),
    })))

    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '20/10/2026' } })
    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '10/10/2026' } })
    expect(await screen.findByRole('alert')).toHaveTextContent('La fecha inicial no puede ser posterior')
  })
})
