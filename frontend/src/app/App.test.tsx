import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

function renderApp(initialEntry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
    sessionStorage.setItem('distribuidora.accessToken', 'test-token')
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

  it('hides administrative navigation when the session has no readable admin authority', () => {
    sessionStorage.setItem('distribuidora.accessToken', 'malformed-token')
    renderApp('/dashboard')

    expect(screen.queryByRole('link', { name: 'Usuarios' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Vendedores' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Auditoría' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Productos' })).toBeInTheDocument()
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
})
