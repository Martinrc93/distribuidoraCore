import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SellersPage from './SellersPage'

function token() { return `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature` }
function response(body: unknown, status = 200) { return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response) }
const sellers = { content: [{ id: 'seller-1', userId: 'user-1', displayName: 'Lucía', email: 'lucia@example.com', status: 'ACTIVE', assignedCustomersCount: 2 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
const users = { content: [{ id: 'user-2', email: 'new@example.com', status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

describe('SellersPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => { vi.restoreAllMocks(); sessionStorage.setItem('distribuidora.accessToken', token()) })

  it('creates a seller profile for a selected user', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input).startsWith('/api/sellers?')) return response(sellers)
      if (String(input).startsWith('/api/users?')) return response(users)
      if (String(input) === '/api/sellers' && init?.method === 'POST') return response({ id: 'seller-2' }, 201)
      return response({})
    })
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><SellersPage /></MemoryRouter></QueryClientProvider>)

    await user.click(await screen.findByRole('button', { name: /nuevo vendedor/i }))
    await user.selectOptions(screen.getByLabelText('Usuario vinculado'), 'user-2')
    await user.type(screen.getByLabelText('Nombre para mostrar'), 'Nuevo vendedor')
    await user.click(screen.getByRole('button', { name: /guardar vendedor/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/sellers', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ userId: 'user-2', displayName: 'Nuevo vendedor' }),
    })))
    expect(await screen.findByText('Vendedor creado correctamente.')).toBeInTheDocument()
  })

  it('reassigns every customer and their pending orders with one confirmed request', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input).startsWith('/api/sellers?')) return response({ ...sellers, content: [
        ...sellers.content,
        { id: 'seller-2', userId: 'user-2', displayName: 'Nuevo', email: 'new@example.com', status: 'ACTIVE', assignedCustomersCount: 0 },
      ] })
      if (String(input).startsWith('/api/users?')) return response(users)
      if (String(input) === '/api/sellers/reassign-customers' && init?.method === 'POST') return response({ sourceSellerId: 'seller-1', targetSellerId: 'seller-2', reassignedCustomersCount: 2, reassignedOrdersCount: 3 })
      return response({})
    })
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><SellersPage /></MemoryRouter></QueryClientProvider>)

    await user.click(await screen.findByRole('button', { name: /reasignar clientes/i }))
    await user.selectOptions(screen.getByLabelText('Vendedor de origen'), 'seller-1')
    await user.selectOptions(screen.getByLabelText('Vendedor de destino'), 'seller-2')
    await user.click(screen.getByLabelText('Reasignar pedidos pendientes'))
    await user.click(screen.getByRole('button', { name: /continuar reasignación/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /confirmar reasignación/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/sellers/reassign-customers', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ sourceSellerId: 'seller-1', targetSellerId: 'seller-2', reassignPendingOrders: true }),
    })))
    expect(await screen.findByText(/2 clientes y 3 pedidos reasignados/i)).toBeInTheDocument()
  })
})
