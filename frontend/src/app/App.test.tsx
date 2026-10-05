import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

function renderApp(initialEntry: string, queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('App shell', () => {
  afterEach(() => {
    cleanup()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('shows the primary navigation and dashboard content', () => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    renderApp('/dashboard')

    expect(screen.getByRole('navigation', { name: /navegación principal/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /resumen operativo/i })).toBeInTheDocument()
  })

  it.each([
    ['/customers', 'Clientes', 'Clientes'],
    ['/products', 'Productos', 'Productos'],
    ['/catalog', 'Marcas y categorías', 'Marcas y categorías'],
    ['/price-lists', 'Listas de precios', 'Listas de precios'],
  ])('renders the extracted %s route inside the authenticated shell', async (route, heading, linkName) => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path.startsWith('/api/customers')) return response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      if (path.startsWith('/api/sellers') || path.startsWith('/api/pricing/lists')) return response({ content: [] })
      if (path.startsWith('/api/brands') || path.startsWith('/api/categories')) return response([])
      if (path.startsWith('/api/products')) return response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return response({})
    })

    renderApp(route)

    expect(screen.getByRole('navigation', { name: /navegación principal/i })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: linkName })).toHaveClass('active')
  })

  it('redirects unauthenticated customer-route visits to login', () => {
    renderApp('/customers')

    expect(screen.getByRole('heading', { name: 'Ingresar' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: /navegación principal/i })).not.toBeInTheDocument()
  })

  it('opens the dashboard after an administrator logs in without reloading', async () => {
    const accessToken = `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/auth/login') return response({ accessToken, refreshToken: 'refresh-token' })
      if (String(input).startsWith('/api/dashboard?')) {
        const params = new URL(String(input), 'http://localhost').searchParams
        return response({ dateMin: params.get('dateMin'), dateMax: params.get('dateMax'), totals: { performedOrders: 3, deliveredOrders: 1, totalBilled: 1500, totalPaid: 1300, cashPaid: 1000, transferPaid: 300, accountBalance: 200 }, bySeller: [] })
      }
      if (String(input) === '/api/zones') return response([])
      return response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    })
    const user = userEvent.setup()
    renderApp('/login')

    await user.type(screen.getByLabelText('Email'), 'admin@example.test')
    await user.type(screen.getByLabelText('Contraseña'), 'test-password')
    await user.click(screen.getByRole('button', { name: 'Ingresar' }))

    expect(await screen.findByRole('heading', { name: 'Resumen operativo' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Totales del período' })).toHaveTextContent('Total facturado')
    expect(screen.getByRole('link', { name: 'Resumen' })).toHaveClass('active')
    expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/dashboard\?dateMin=\d{4}-\d{2}-\d{2}&dateMax=\d{4}-\d{2}-\d{2}$/), expect.any(Object))

    await user.click(screen.getByRole('link', { name: 'Clientes' }))
    await screen.findByRole('heading', { name: 'Clientes' })
    await user.click(screen.getByRole('link', { name: 'Resumen' }))

    expect(await screen.findByRole('heading', { name: 'Resumen operativo' })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Zonas' }))
    expect(await screen.findByRole('heading', { name: 'Zonas' })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Auditoría' }))
    expect(await screen.findByRole('heading', { name: 'Auditoría' })).toBeInTheDocument()
  })

  it.each(['/dashboard', '/admin/zones', '/admin/audit', '/customers', '/customers/customer-1', '/products', '/catalog', '/price-lists'])('keeps %s restricted to administrators', async (route) => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['SELLER'] }))}.signature`)
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }))
    renderApp(route)

    expect(await screen.findByRole('heading', { name: 'Pedidos' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Resumen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Clientes' })).not.toBeInTheDocument()
    for (const name of ['Productos', 'Marcas y categorías', 'Listas de precios']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
    expect(fetchMock.mock.calls.some(([input]) => ['/api/dashboard', '/api/zones', '/api/audit', '/api/customers', '/api/products', '/api/brands', '/api/categories', '/api/pricing'].some((path) => String(input).split('?')[0].startsWith(path)))).toBe(false)
  })

  it('hides administrative navigation when the session has no readable admin authority', () => {
    sessionStorage.setItem('distribuidora.accessToken', 'malformed-token')
    renderApp('/dashboard')

    expect(screen.queryByRole('link', { name: 'Usuarios' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Vendedores' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Auditoría' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Productos' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Pedidos' })).toBeInTheDocument()
  })

  it('revokes the refresh session and returns to login when signing out', async () => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-token')
    sessionStorage.setItem('distribuidora.refreshToken', 'refresh-token')
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/auth/logout') return Promise.resolve(new Response(null, { status: 204 }))
      return response({ confirmedOrders: 0, todaySales: 0, pendingBalance: 0, negativeStock: 0, recentOrders: [] })
    })
    const user = userEvent.setup()
    renderApp('/dashboard')

    await user.click(screen.getByRole('button', { name: 'Salir' }))

    expect(await screen.findByRole('heading', { name: 'Ingresar' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: 'refresh-token' }),
    })
    expect(sessionStorage.getItem('distribuidora.accessToken')).toBeNull()
  })

  it.each([['ADMIN_ALL'], ['ORDER_CREATE']])('does not reuse %s customers, orders or sales after another seller logs in', async (authority) => {
    const firstToken = `header.${btoa(JSON.stringify({ sub: 'first-user', authorities: [authority] }))}.signature`
    const secondToken = `header.${btoa(JSON.stringify({ sub: 'second-user', authorities: ['ORDER_CREATE'] }))}.signature`
    sessionStorage.setItem('distribuidora.accessToken', firstToken)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    const customersKey = ['/api/customers?page=0&size=20']
    queryClient.setQueryData(customersKey, { content: [{ id: 'first-customer', name: 'First customer' }] })
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/auth/login') return response({ accessToken: secondToken, refreshToken: null })
      if (path.includes('filter-options')) return response({ customers: [], sellers: [] })
      if (path === '/api/zones') return response([])
      const owner = (init?.headers as Record<string, string> | undefined)?.Authorization === `Bearer ${secondToken}` ? 'Second' : 'First'
      const kind = path.startsWith('/api/customers?') ? 'customer' : path.startsWith('/api/orders?') ? 'order' : path.startsWith('/api/sales?') ? 'sale' : ''
      return response({ content: kind ? [{ id: `${owner}-${kind}`, name: `${owner} customer`, number: `${owner} ${kind}`, customer: `${owner} customer`, seller: `${owner} seller`, cuitId: '', balance: 0, total: 10, paid: 10, status: kind === 'customer' ? 'ACTIVE' : 'CONFIRMED', date: '2026-10-02T12:00:00Z' }] : [], page: 0, size: 20, totalElements: kind ? 1 : 0, totalPages: kind ? 1 : 0 })
    })
    const user = userEvent.setup()
    renderApp(authority === 'ADMIN_ALL' ? '/customers' : '/orders', queryClient)
    await screen.findByText(authority === 'ADMIN_ALL' ? 'First customer' : 'First order')
    await user.click(screen.getByRole('link', { name: 'Ventas' }))
    await screen.findByText('First sale')
    await user.click(screen.getByRole('link', { name: 'Pedidos' }))
    await screen.findByText('First order')
    await user.click(screen.getByRole('button', { name: 'Salir' }))
    await screen.findByRole('heading', { name: 'Ingresar' })
    await user.type(screen.getByLabelText('Email'), 'second-seller@example.test')
    await user.type(screen.getByLabelText('Contraseña'), 'test-password')
    await user.click(screen.getByRole('button', { name: 'Ingresar' }))
    await screen.findByText('Second order')
    expect(screen.queryByText('First order')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Clientes' })).not.toBeInTheDocument()
    expect(queryClient.getQueryData(customersKey)).toBeUndefined()
    await user.click(screen.getByRole('link', { name: 'Ventas' }))
    await screen.findByText('Second sale')
    expect(screen.queryByText('First sale')).not.toBeInTheDocument()
  })

  it('discards previously cached customers when logging in after a session ended', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    const customersPath = '/api/customers?page=0&size=20&search=&sellerId=&hasBalance=false&status=ACTIVE'
    const page = { page: 0, size: 20, totalElements: 1, totalPages: 1 }
    queryClient.setQueryData([customersPath], { ...page, content: [{ id: 'previous-customer', name: 'Previous account customer', status: 'ACTIVE', balance: 0 }] })
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/auth/login') return response({ accessToken: `header.${btoa(JSON.stringify({ authorities: ['ORDER_CREATE'] }))}.signature`, refreshToken: null })
      if (String(input) === customersPath) return response({ ...page, content: [{ id: 'own-customer', name: 'Current seller customer', status: 'ACTIVE', balance: 0 }] })
      return response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    })
    const user = userEvent.setup()
    renderApp('/login', queryClient)
    await user.type(screen.getByLabelText('Email'), 'seller@example.test')
    await user.type(screen.getByLabelText('Contraseña'), 'test-password')
    await user.click(screen.getByRole('button', { name: 'Ingresar' }))
    await screen.findByRole('heading', { name: 'Pedidos' })
    expect(screen.queryByRole('link', { name: 'Clientes' })).not.toBeInTheDocument()
    expect(queryClient.getQueryData([customersPath])).toBeUndefined()
  })
})
