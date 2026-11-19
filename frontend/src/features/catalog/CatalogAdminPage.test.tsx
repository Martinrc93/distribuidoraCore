import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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

const brands = [{ id: 'brand-1', name: 'Molino Norte', status: 'ACTIVE', productCount: 2 }]
const categories = [{ id: 'category-1', name: 'Almacén', status: 'ACTIVE', productCount: 4 }]

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
    expect(screen.getByRole('dialog', { name: 'Nueva marca' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Nombre de marca'), 'Lácteos del Sur')
    await user.click(screen.getByRole('button', { name: /guardar marca/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/brands', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'Lácteos del Sur' }),
    })))
    expect(await screen.findByText('Marca creada correctamente.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /desactivar marca molino norte/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^desactivar marca$/i }))
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
    await user.click(screen.getByRole('button', { name: /guardar categoría/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/categories', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'Bebidas' }),
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

  it('offers permanent removal only for unused categories and waits for confirmation', async () => {
    const user = userEvent.setup()
    const emptyCategory = { id: 'empty', name: 'Sin productos', status: 'ACTIVE', productCount: 0 }
    let visibleCategories = [...categories, emptyCategory]
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/brands') return response(brands)
      if (String(input) === '/api/categories') return response(visibleCategories)
      if (String(input) === '/api/categories/empty/permanent' && init?.method === 'DELETE') {
        visibleCategories = categories
        return response({}, 204)
      }
      return response({})
    })
    renderPage()

    await user.click(screen.getByRole('tab', { name: 'Categorías' }))
    const remove = await screen.findByRole('button', { name: 'Eliminar categoría Sin productos' })
    expect(screen.queryByRole('button', { name: 'Eliminar categoría Almacén' })).not.toBeInTheDocument()
    await user.click(remove)
    const confirmation = screen.getByRole('dialog', { name: '¿Eliminar «Sin productos»?' })
    expect(within(confirmation).getByRole('button', { name: 'Cancelar' })).toHaveFocus()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/categories/empty/permanent', expect.anything())
    await user.click(within(confirmation).getByRole('button', { name: 'Eliminar categoría' }))

    expect(await screen.findByText('Categoría eliminada correctamente.')).toBeVisible()
    expect(screen.queryByText('Sin productos')).not.toBeInTheDocument()
    expect(screen.getByText('Almacén')).toBeVisible()
  })

  it('shows the reason when deleting an unused-looking brand is blocked', async () => {
    const user = userEvent.setup()
    const unusedBrand = { id: 'unused', name: 'Marca de prueba', status: 'INACTIVE', productCount: 0 }
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/brands') return response([...brands, unusedBrand])
      if (String(input) === '/api/categories') return response(categories)
      if (String(input) === '/api/brands/unused/permanent' && init?.method === 'DELETE') {
        return response({ detail: 'La marca está en uso por productos o reglas de comisión.' }, 409)
      }
      return response({})
    })
    renderPage()

    expect(await screen.findByText('Molino Norte')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Eliminar marca Molino Norte' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Eliminar marca Marca de prueba' }))
    const confirmation = screen.getByRole('dialog', { name: '¿Eliminar «Marca de prueba»?' })
    await user.click(within(confirmation).getByRole('button', { name: 'Eliminar marca' }))
    expect(await within(confirmation).findByRole('alert')).toHaveTextContent('reglas de comisión')
    await user.click(within(confirmation).getByRole('button', { name: 'Cancelar' }))
    expect(screen.getByRole('button', { name: 'Eliminar marca Marca de prueba' })).toHaveFocus()
  })
})
