import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CatalogAdminPage from './CatalogAdminPage'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const brands = [{ id: 'brand-1', name: 'Molino Norte', code: 'MN', status: 'ACTIVE', productCount: 2 }]
const categories = [{ id: 'category-1', name: 'Almacén', code: 'ALM', status: 'ACTIVE', productCount: 4 }]

function renderPage(authorities = ['ADMIN_ALL']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><CatalogAdminPage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('CatalogAdminPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => { vi.restoreAllMocks() })

  it('creates and deactivates a brand using the catalog administration API', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/brands' && !init?.method) return response(brands)
      if (String(input) === '/api/categories' && !init?.method) return response(categories)
      if (String(input) === '/api/brands' && init?.method === 'POST') return response({ id: 'brand-2' }, 201)
      if (String(input) === '/api/brands/brand-1' && init?.method === 'DELETE') return response({}, 204)
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nueva marca/i }))
    await user.type(screen.getByLabelText('Nombre de marca'), 'Lácteos del Sur')
    await user.type(screen.getByLabelText('Código de marca'), 'LDS')
    await user.click(screen.getByRole('button', { name: /guardar marca/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/brands', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'Lácteos del Sur', code: 'LDS' }),
    })))
    expect(await screen.findByText('Marca creada correctamente.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /eliminar marca molino norte/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /confirmar/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/brands/brand-1', expect.objectContaining({ method: 'DELETE' })))
  })

  it('creates a category and displays duplicate-name errors without closing the form', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/brands' && !init?.method) return response(brands)
      if (String(input) === '/api/categories' && !init?.method) return response(categories)
      if (String(input) === '/api/categories' && init?.method === 'POST') return response({ detail: 'La categoría ya existe' }, 409)
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('tab', { name: 'Categorías' }))
    await user.click(await screen.findByRole('button', { name: /nueva categoría/i }))
    await user.type(screen.getByLabelText('Nombre de categoría'), 'Bebidas')
    await user.type(screen.getByLabelText('Código de categoría'), 'BEB')
    await user.click(screen.getByRole('button', { name: /guardar categoría/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/categories', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'Bebidas', code: 'BEB' }),
    })))
    expect(await screen.findByText('La categoría ya existe')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar categoría/i })).toBeInTheDocument()
  })

  it('hides catalog mutations for non-admin users', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input) === '/api/brands' ? response(brands) : response(categories))
    renderPage(['ORDER_CREATE'])

    expect(await screen.findByText('Molino Norte')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nueva marca/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nueva categoría/i })).not.toBeInTheDocument()
  })
})
