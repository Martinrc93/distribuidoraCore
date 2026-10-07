import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CustomersPage from './CustomersPage'
import { selectEntity } from '../../test/selectEntity'

function token(authorities: string[]) {
  const payload = btoa(JSON.stringify({ authorities }))
  return `header.${payload}.signature`
}

function renderPage(authorities = ['ADMIN_ALL']) {
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
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

function mockFetch(handler: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  return vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
    if (String(input) === '/api/zones') return response([])
    if (String(input) === '/api/customers/filter-options') return response({ sellers: [] })
    return handler(input, init)
  })
}

const customers = { content: [{ id: 'customer-1', name: 'Almacén Norte', cuitId: '30-123', seller: 'Lucía', balance: 1000, status: 'ACTIVE', priceListId: 'list-1' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

describe('CustomersPage', () => {
  it('shows assigned customers as a read-only list for sellers', async () => {
    const fetchMock = mockFetch(() => response(customers))
    renderPage(['ORDER_CREATE'])
    expect(await screen.findByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.getByText('Consultá únicamente los clientes asignados a tu vendedor.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo cliente|editar|activar cliente|desactivar cliente/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /ver cliente/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Buscar por vendedor' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('columnheader')).toHaveLength(5)
    expect(fetchMock.mock.calls.every(([input]) => String(input).startsWith('/api/customers?'))).toBe(true)
  })

  it('preserves customer edits until discard is confirmed through the shared dialog', async () => {
    const user = userEvent.setup()
    mockFetch((input) => String(input).startsWith('/api/customers') ? response(customers) : response({ content: [] }))
    renderPage()
    await user.click(await screen.findByRole('button', { name: /nuevo cliente/i }))
    await user.type(screen.getByLabelText(/razón social/i), 'Cliente pendiente')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    const dialog = screen.getByRole('dialog', { name: '¿Descartar los cambios?' })
    await user.click(within(dialog).getByRole('button', { name: 'Seguir editando' }))
    expect(screen.getByLabelText(/razón social/i)).toHaveValue('Cliente pendiente')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
    expect(screen.queryByLabelText(/razón social/i)).not.toBeInTheDocument()
  })

  afterEach(() => cleanup())

  beforeEach(() => {
    sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
    vi.restoreAllMocks()
  })

  it('shows a dash when a customer has no CUIT', async () => {
    const customerWithoutTaxId = { ...customers.content[0], cuitId: null }
    mockFetch((input) => String(input).startsWith('/api/customers')
      ? response({ ...customers, content: [customerWithoutTaxId] })
      : response({ content: [] }))
    renderPage([])

    const row = await screen.findByRole('row', { name: /Almacén Norte/ })
    expect(within(row).getAllByRole('cell')[1]).toHaveTextContent('-')
  })

  it('creates a customer with seller assignment and invalidates the exact list query', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch((input, init) => {
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
    await selectEntity(user, 'Vendedor asignado', 'Lucía (lucia@test)')
    await user.click(screen.getByRole('button', { name: /guardar cliente/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/customers', expect.objectContaining({ method: 'POST', body: JSON.stringify({ businessName: 'Despensa Centro', cuitId: '30-456', email: null, phone: null, address: null, zone: null, sellerId: 'seller-1' }) })))
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
    expect(await screen.findByText('Cliente creado correctamente.')).toBeInTheDocument()
  })

  it('creates a customer without a tax identification', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch((input, init) => {
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
      body: JSON.stringify({ businessName: 'Cliente Eventual', cuitId: null, email: null, phone: null, address: null, zone: null, sellerId: undefined }),
    })))
  })

  it('edits a customer, assigns a price list, and confirms status changes', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch((input, init) => {
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
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /guardar cambios/i })).not.toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /editar/i }))
    await user.click(screen.getByRole('button', { name: /desactivar cliente/i }))
    expect(screen.getByRole('dialog', { name: /desactivar a/i })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /confirmar/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/customers/customer-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ businessName: 'Almacén Sur', cuitId: '30-123', email: null, phone: null, address: null, zone: null, sellerId: undefined, priceListId: 'list-2' }) })))
    expect(fetchMock).toHaveBeenCalledWith('/api/customers/customer-1/status', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ status: 'INACTIVE' }) }))
    expect(await screen.findByText('Cliente desactivado correctamente.')).toBeInTheDocument()
  })

  it('synchronizes the form when switching between customers', async () => {
    const user = userEvent.setup()
    const secondCustomer = { ...customers.content[0], id: 'customer-2', name: 'Almacén Sur', cuitId: '30-456' }
    mockFetch((input) => String(input).startsWith('/api/customers?page=') ? response({ ...customers, content: [customers.content[0], secondCustomer] }) : response({ content: [] }))
    renderPage()

    const editButtons = await screen.findAllByRole('button', { name: /editar/i })
    await user.click(editButtons[0])
    await user.click(screen.getAllByRole('button', { name: /editar/i })[1])

    expect(screen.getByLabelText(/razón social/i)).toHaveValue('Almacén Sur')
    expect(screen.getByLabelText(/cuit/i)).toHaveValue('30-456')
  })

  it.each([
    ['/api/customers/customer-1', 'PUT'],
    ['/api/customers/customer-1/status', 'PATCH'],
  ])('invalidates customers after a 404 from %s', async (path, method) => {
    const user = userEvent.setup()
    const fetchMock = mockFetch((input, init) => {
      const requestPath = String(input)
      if (requestPath.startsWith('/api/customers?page=') && !init?.method) return response(customers)
      if (requestPath.startsWith('/api/sellers')) return response({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      if (requestPath.startsWith('/api/pricing/lists')) return response({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      if (requestPath === path && init?.method === method) return response({ detail: 'gone' }, 404)
      return response({}, 204)
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    if (path.endsWith('/status')) {
      await user.click(await screen.findByRole('button', { name: /editar/i }))
      await user.click(await screen.findByRole('button', { name: /desactivar cliente/i }))
      await user.click(screen.getByRole('button', { name: /confirmar/i }))
    } else {
      await user.click(await screen.findByRole('button', { name: /editar/i }))
      await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    }

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(path, expect.objectContaining({ method })))
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
  })

  it('saves price-list assignments through the customer form and shows feedback', async () => {
    const user = userEvent.setup()
    mockFetch((input, init) => {
      const path = String(input)
      if (path.startsWith('/api/customers?page=') && !init?.method) return response(customers)
      if (path.startsWith('/api/sellers')) return response({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      if (path.startsWith('/api/pricing/lists')) return response({ content: [{ id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }], page: 0, size: 100, totalElements: 1, totalPages: 1 })
      return response({}, 204)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.selectOptions(screen.getByLabelText('Lista de precios predeterminada'), 'list-1')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('Cliente actualizado correctamente.')).toBeInTheDocument()
  })

  it('shows actionable selector query errors', async () => {
    mockFetch((input) => {
      const path = String(input)
      if (path.startsWith('/api/customers?page=')) return response(customers)
      if (path.startsWith('/api/sellers')) return response({}, 500)
      if (path.startsWith('/api/pricing/lists')) return response({}, 500)
      return response({})
    })
    renderPage()
    await userEvent.setup().click(await screen.findByRole('button', { name: /editar/i }))
    expect(await screen.findByText('No se pudieron cargar los vendedores.')).toBeInTheDocument()
    expect(await screen.findByText('No se pudieron cargar las listas.')).toBeInTheDocument()
  })

  it('hides admin-only seller and mutation actions for non-admins', async () => {
    sessionStorage.setItem('distribuidora.accessToken', token([]))
    mockFetch((input) => String(input).startsWith('/api/customers') ? response(customers) : response({ content: [] }))
    renderPage([])

    expect(await screen.findByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo cliente/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /editar/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/vendedor/i)).not.toBeInTheDocument()
  })

  it('keeps the form open and shows an actionable conflict error', async () => {
    const user = userEvent.setup()
    mockFetch((input, init) => {
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
    mockFetch((input, init) => {
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
    mockFetch((input, init) => {
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
