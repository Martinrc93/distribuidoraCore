import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProductsPage from './ProductsPage'

function token(authorities: string[]) {
  const payload = btoa(JSON.stringify({ authorities }))
  return `header.${payload}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

function renderPage(authorities = ['ADMIN_ALL']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter><ProductsPage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

const products = {
  content: [{ id: 'product-1', sku: 'SKU-1', name: 'Harina', category: 'Almacén', presentation: 'Bolsa', cost: 100.5, price: 150.75, stock: 4, status: 'ACTIVE' }],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
}

describe('ProductsPage', () => {
  afterEach(() => cleanup())

  beforeEach(() => {
    sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
    vi.restoreAllMocks()
  })

  it('creates a product with numeric payloads and invalidates the exact list query', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response({ ...products, content: [] })
      if (String(input) === '/api/products' && init?.method === 'POST') return response({ id: 'new-product' }, 201)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-2')
    await user.type(screen.getByLabelText('Nombre'), 'Arroz')
    await user.type(screen.getByLabelText('Categoría'), 'Almacén')
    await user.clear(screen.getByLabelText('Costo'))
    await user.type(screen.getByLabelText('Costo'), '12.5')
    await user.clear(screen.getByLabelText('Precio'))
    await user.type(screen.getByLabelText('Precio'), '20.75')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products', expect.objectContaining({ method: 'POST', body: JSON.stringify({ sku: 'SKU-2', name: 'Arroz', category: 'Almacén', presentation: 'Unidad', cost: 12.5, price: 20.75 }) })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/products?page=0&size=20'] })
    expect(await screen.findByText('Producto creado correctamente.')).toBeInTheDocument()
  })

  it('edits a product and sends numeric values', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      return response({}, init?.method === 'PUT' ? 204 : 200)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.clear(screen.getByLabelText('Precio'))
    await user.type(screen.getByLabelText('Precio'), '175.25')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products/product-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ sku: 'SKU-1', name: 'Harina', category: 'Almacén', presentation: 'Bolsa', cost: 100.5, price: 175.25 }) })))
    expect(await screen.findByText('Producto actualizado correctamente.')).toBeInTheDocument()
  })

  it('rejects negative prices locally without making a mutation request', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => String(input).includes('products?page=') ? response({ ...products, content: [] }) : response({}))
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-2')
    await user.type(screen.getByLabelText('Nombre'), 'Arroz')
    await user.type(screen.getByLabelText('Categoría'), 'Almacén')
    await user.type(screen.getByLabelText('Costo'), '1')
    await user.clear(screen.getByLabelText('Precio'))
    await user.type(screen.getByLabelText('Precio'), '-1')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(await screen.findByText('El costo y el precio deben ser números no negativos.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/products', expect.objectContaining({ method: 'POST' }))
  })

  it('requires confirmation before an admin changes product status', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      return response({}, init?.method === 'PATCH' ? 204 : 200)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /desactivar producto/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/products/product-1/status', expect.objectContaining({ method: 'PATCH' }))
    await user.click(screen.getByRole('button', { name: /confirmar/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products/product-1/status', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ status: 'INACTIVE' }) })))
  })

  it('hides admin actions for non-admins and keeps duplicate SKU errors actionable', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('distribuidora.accessToken', token([]))
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      return response({ detail: 'El SKU ya existe' }, 409)
    })
    renderPage([])

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo producto/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /editar/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /desactivar producto/i })).not.toBeInTheDocument()

    sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
    cleanup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-1')
    await user.type(screen.getByLabelText('Nombre'), 'Duplicado')
    await user.type(screen.getByLabelText('Categoría'), 'Almacén')
    await user.type(screen.getByLabelText('Costo'), '10')
    await user.type(screen.getByLabelText('Precio'), '20')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(await screen.findByText('El SKU ya existe')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar producto/i })).toBeInTheDocument()
  })
})
