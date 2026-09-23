import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CustomersPage from './CustomersPage'

function token(authorities: string[]) {
  const payload = btoa(JSON.stringify({ authorities }))
  return `header.${payload}.signature`
}

function renderPage(authorities = ['ADMIN_ALL']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><CustomersPage /></MemoryRouter>
    </QueryClientProvider>,
  )
  return queryClient
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const customers = { content: [{ id: 'customer-1', name: 'Almacén Norte', cuitId: '30-123', seller: 'Lucía', balance: 1000, status: 'ACTIVE', priceListId: 'list-1' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

describe('CustomersPage', () => {
  afterEach(() => cleanup())

  beforeEach(() => {
    sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
    vi.restoreAllMocks()
  })

  it('creates a customer with seller assignment and invalidates the exact list query', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/customers') && !init?.method) return response(customers)
      if (path.startsWith('/api/sellers')) return response({ content: [{ id: 'seller-1', displayName: 'Lucía', email: 'lucia@test' }] })
      if (path.startsWith('/api/pricing/lists')) return response({ content: [{ id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }] })
      if (path === '/api/customers' && init?.method === 'POST') return response({ id: 'new-customer' }, 201)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /nuevo cliente/i }))
    await user.type(screen.getByLabelText(/razón social/i), 'Despensa Centro')
    await user.type(screen.getByLabelText(/cuit/i), '30-456')
    await user.selectOptions(screen.getByLabelText(/vendedor/i), 'seller-1')
    await user.click(screen.getByRole('button', { name: /guardar cliente/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/customers', expect.objectContaining({ method: 'POST', body: JSON.stringify({ businessName: 'Despensa Centro', cuitId: '30-456', sellerId: 'seller-1' }) })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/customers?page=0&size=20'] })
    expect(await screen.findByText('Cliente creado correctamente.')).toBeInTheDocument()
  })

  it('creates a customer without a tax identification', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/customers') && !init?.method) return response(customers)
      if (path.startsWith('/api/sellers')) return response({ content: [] })
      if (path === '/api/customers' && init?.method === 'POST') return response({ id: 'new-customer' }, 201)
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nuevo cliente/i }))
    await user.type(screen.getByLabelText(/razón social/i), 'Cliente Eventual')
    await user.click(screen.getByRole('button', { name: /guardar cliente/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/customers', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ businessName: 'Cliente Eventual', cuitId: null, sellerId: undefined }),
    })))
  })

  it('edits a customer, assigns a price list, and confirms status changes', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/customers') && !init?.method) return response(customers)
      if (path.startsWith('/api/sellers')) return response({ content: [] })
      if (path.startsWith('/api/pricing/lists')) return response({ content: [{ id: 'list-2', code: 'PREMIUM', name: 'Premium', status: 'ACTIVE' }] })
      return response({}, 204)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    expect(screen.getByLabelText(/razón social/i)).toHaveValue('Almacén Norte')
    await user.clear(screen.getByLabelText(/razón social/i))
    await user.type(screen.getByLabelText(/razón social/i), 'Almacén Sur')
    await user.selectOptions(await screen.findByLabelText(/lista de precios/i), 'list-2')
    await user.click(screen.getByRole('button', { name: /asignar lista/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /guardar cambios/i })).not.toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /desactivar cliente/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /confirmar/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/customers/customer-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ businessName: 'Almacén Sur', cuitId: '30-123', sellerId: undefined }) })))
    expect(fetchMock).toHaveBeenCalledWith('/api/customers/customer-1/price-list', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ priceListId: 'list-2' }) }))
    expect(fetchMock).toHaveBeenCalledWith('/api/customers/customer-1/status', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ status: 'INACTIVE' }) }))
    expect(await screen.findByText('Cliente desactivado correctamente.')).toBeInTheDocument()
  })

  it('synchronizes the form when switching between customers', async () => {
    const user = userEvent.setup()
    const secondCustomer = { ...customers.content[0], id: 'customer-2', name: 'Almacén Sur', cuitId: '30-456' }
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).startsWith('/api/customers?page=') ? response({ ...customers, content: [customers.content[0], secondCustomer] }) : response({ content: [] }))
    renderPage()

    const editButtons = await screen.findAllByRole('button', { name: /editar/i })
    await user.click(editButtons[0])
    await user.click(screen.getAllByRole('button', { name: /editar/i })[1])

    expect(screen.getByLabelText(/razón social/i)).toHaveValue('Almacén Sur')
    expect(screen.getByLabelText(/cuit/i)).toHaveValue('30-456')
  })

  it.each([
    ['/api/customers/customer-1', 'PUT'],
    ['/api/customers/customer-1/price-list', 'PATCH'],
    ['/api/customers/customer-1/status', 'PATCH'],
  ])('invalidates customers after a 404 from %s', async (path, method) => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const requestPath = String(input)
      if (requestPath.startsWith('/api/customers?page=') && !init?.method) return response(customers)
      if (requestPath.startsWith('/api/sellers')) return response({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      if (requestPath.startsWith('/api/pricing/lists')) return response({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      if (requestPath === path && init?.method === method) return response({ detail: 'gone' }, 404)
      return response({}, 204)
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    if (path.endsWith('/price-list')) {
      await user.click(await screen.findByRole('button', { name: /editar/i }))
      await user.click(await screen.findByRole('button', { name: /asignar lista/i }))
    } else if (path.endsWith('/status')) {
      await user.click(await screen.findByRole('button', { name: /desactivar cliente/i }))
      await user.click(screen.getByRole('button', { name: /confirmar/i }))
    } else {
      await user.click(await screen.findByRole('button', { name: /editar/i }))
      await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    }

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(path, expect.objectContaining({ method })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/customers?page=0&size=20'] })
  })

  it('shows customer mutation feedback for update and price-list assignment', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/customers?page=') && !init?.method) return response(customers)
      if (path.startsWith('/api/sellers')) return response({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      if (path.startsWith('/api/pricing/lists')) return response({ content: [{ id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }], page: 0, size: 100, totalElements: 1, totalPages: 1 })
      return response({}, 204)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('Cliente actualizado correctamente.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /editar/i }))
    await user.click(await screen.findByRole('button', { name: /asignar lista/i }))
    expect(await screen.findByText('Lista de precios asignada correctamente.')).toBeInTheDocument()
  })

  it('shows actionable selector query errors', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path.startsWith('/api/customers?page=')) return response(customers)
      if (path.startsWith('/api/sellers')) return response({}, 500)
      if (path.startsWith('/api/pricing/lists')) return response({}, 500)
      return response({})
    })
    renderPage()
    await userEvent.setup().click(await screen.findByRole('button', { name: /editar/i }))
    expect(await screen.findByText('No se pudieron cargar los vendedores.')).toBeInTheDocument()
    expect(await screen.findByText('No se pudieron cargar las listas de precios.')).toBeInTheDocument()
  })

  it('hides admin-only seller and mutation actions for non-admins', async () => {
    sessionStorage.setItem('distribuidora.accessToken', token([]))
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).startsWith('/api/customers') ? response(customers) : response({ content: [] }))
    renderPage()

    expect(await screen.findByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo cliente/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /editar/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/vendedor/i)).not.toBeInTheDocument()
  })

  it('keeps the form open and shows an actionable conflict error', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input).startsWith('/api/customers') && !init?.method) return response({ ...customers, content: [] })
      if (init?.method === 'POST') return response({ detail: 'El CUIT ya existe' }, 409)
      return response({ content: [] })
    })
    renderPage()
    await user.click(await screen.findByRole('button', { name: /nuevo cliente/i }))
    await user.type(screen.getByLabelText(/razón social/i), 'Duplicado')
    await user.type(screen.getByLabelText(/cuit/i), '30-123')
    await user.click(screen.getByRole('button', { name: /guardar cliente/i }))

    expect(await screen.findByText('El CUIT ya existe')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cliente/i })).toBeInTheDocument()
  })

  it('uses the mutation status before misleading error text for forbidden edits', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/customers') && !init?.method) return response(customers)
      if (path === '/api/customers/customer-1' && init?.method === 'PUT') return response({ detail: 'conflict' }, 403)
      return response({ content: [] })
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    expect(await screen.findByText('No tenés permisos para realizar esta operación.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeInTheDocument()
  })

  it('uses the mutation status before misleading error text for conflicts', async () => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/customers') && !init?.method) return response(customers)
      if (path === '/api/customers/customer-1' && init?.method === 'PUT') return response({ detail: 'forbidden' }, 409)
      return response({ content: [] })
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    expect(await screen.findByText('forbidden')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeInTheDocument()
  })
})
