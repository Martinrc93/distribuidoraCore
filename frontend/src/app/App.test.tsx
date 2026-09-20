import { cleanup, render, screen } from '@testing-library/react'
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
    sessionStorage.setItem('distribuidora.accessToken', 'test-token')
    renderApp('/dashboard')

    expect(screen.getByRole('navigation', { name: /navegación principal/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /resumen operativo/i })).toBeInTheDocument()
  })

  it.each([
    ['/customers', 'Clientes', 'Clientes'],
    ['/products', 'Productos', 'Productos'],
  ])('renders the extracted %s route inside the authenticated shell', async (route, heading, linkName) => {
    sessionStorage.setItem('distribuidora.accessToken', 'test-token')
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path.startsWith('/api/customers')) return response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      if (path.startsWith('/api/sellers') || path.startsWith('/api/pricing/lists')) return response({ content: [] })
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
})
