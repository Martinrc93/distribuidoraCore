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
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
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
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.clear(screen.getByLabelText('Precio'))
    await user.type(screen.getByLabelText('Precio'), '175.25')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products/product-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ sku: 'SKU-1', name: 'Harina', category: 'Almacén', presentation: 'Bolsa', cost: 100.5, price: 175.25 }) })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/products?page=0&size=20'] })
    expect(await screen.findByText('Producto actualizado correctamente.')).toBeInTheDocument()
  })

  it('rejects blank, negative, and non-numeric prices locally without making mutation requests', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => String(input).includes('products?page=') ? response({ ...products, content: [] }) : response({}))
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-2')
    await user.type(screen.getByLabelText('Nombre'), 'Arroz')
    await user.type(screen.getByLabelText('Categoría'), 'Almacén')
    await user.type(screen.getByLabelText('Costo'), '1')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(await screen.findByText('El costo y el precio deben ser números no negativos.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/products', expect.objectContaining({ method: 'POST' }))

    await user.type(screen.getByLabelText('Precio'), '-1')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(await screen.findByText('El costo y el precio deben ser números no negativos.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/products', expect.objectContaining({ method: 'POST' }))

    await user.clear(screen.getByLabelText('Precio'))
    await user.type(screen.getByLabelText('Precio'), 'abc')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(await screen.findByText('El costo y el precio deben ser números no negativos.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/products', expect.objectContaining({ method: 'POST' }))
  })

  it('synchronizes the form when switching between products', async () => {
    const user = userEvent.setup()
    const secondProduct = { ...products.content[0], id: 'product-2', sku: 'SKU-2', name: 'Aceite', presentation: 'Botella', cost: 200, price: 250 }
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input) === '/api/products?page=0&size=20' ? response({ ...products, content: [products.content[0], secondProduct] }) : response({}, 204))
    renderPage()

    const editButtons = await screen.findAllByRole('button', { name: /editar/i })
    await user.click(editButtons[0])
    expect(screen.getByLabelText('Nombre')).toHaveValue('Harina')
    await user.click(screen.getAllByRole('button', { name: /editar/i })[1])

    expect(screen.getByLabelText('SKU')).toHaveValue('SKU-2')
    expect(screen.getByLabelText('Nombre')).toHaveValue('Aceite')
    expect(screen.getByLabelText('Presentación')).toHaveValue('Botella')
    expect(screen.getByLabelText('Costo')).toHaveValue('200')
  })

  it('disables all product form controls while saving', async () => {
    const user = userEvent.setup()
    let resolveSave!: (value: Response) => void
    const save = new Promise<Response>((resolve) => { resolveSave = resolve })
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response({ ...products, content: [] })
      if (String(input) === '/api/products' && init?.method === 'POST') return save
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-2')
    await user.type(screen.getByLabelText('Nombre'), 'Arroz')
    await user.type(screen.getByLabelText('Categoría'), 'Almacén')
    await user.type(screen.getByLabelText('Costo'), '10')
    await user.type(screen.getByLabelText('Precio'), '20')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(screen.getByLabelText('SKU')).toBeDisabled()
    expect(screen.getByLabelText('Presentación')).toBeDisabled()
    expect(screen.getByLabelText('Costo')).toBeDisabled()
    expect(screen.getByRole('button', { name: /cancelar/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
    resolveSave(await response({ id: 'new-product' }, 201))
  })

  it('disables page create, edit, and status actions while saving a product', async () => {
    const user = userEvent.setup()
    let resolveSave!: (value: Response) => void
    const save = new Promise<Response>((resolve) => { resolveSave = resolve })
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      if (String(input) === '/api/products' && init?.method === 'POST') return save
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-2')
    await user.type(screen.getByLabelText('Nombre'), 'Arroz')
    await user.type(screen.getByLabelText('Categoría'), 'Almacén')
    await user.type(screen.getByLabelText('Costo'), '10')
    await user.type(screen.getByLabelText('Precio'), '20')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(screen.getByRole('button', { name: /nuevo producto/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /editar/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /desactivar producto/i })).toBeDisabled()
    resolveSave(await response({ id: 'new-product' }, 201))
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

  it('disables modal cancellation while status mutation is running and invalidates the list', async () => {
    const user = userEvent.setup()
    let resolveStatus!: (value: Response) => void
    const statusSave = new Promise<Response>((resolve) => { resolveStatus = resolve })
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      if (String(input).endsWith('/status')) return statusSave
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /desactivar producto/i }))
    await user.click(screen.getByRole('button', { name: /confirmar/i }))
    expect(screen.getByRole('button', { name: /cancelar/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /guardando/i })).toBeDisabled()
    resolveStatus(await response({}, 204))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products/product-1/status', expect.objectContaining({ method: 'PATCH' })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/products?page=0&size=20'] })
  })

  it.each([
    [400, 'Datos inválidos', 'Datos inválidos'],
    [403, 'conflict', 'No tenés permisos para realizar esta operación.'],
    [404, 'forbidden', 'El producto ya no existe o no está disponible.'],
    [409, 'El SKU ya existe', 'El SKU ya existe'],
  ])('maps status %s before misleading error text', async (status, detail, message) => {
    const user = userEvent.setup()
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      return response({ detail }, status)
    })
    renderPage()
    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeInTheDocument()
  })

  it('shows product-specific empty and read errors', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response({ ...products, content: [] }))
    renderPage([])
    expect(await screen.findByText('Todavía no hay productos')).toBeInTheDocument()

    cleanup()
    vi.restoreAllMocks()
    vi.spyOn(global, 'fetch').mockImplementation(() => response({}, 500))
    renderPage([])
    expect(await screen.findByText('No se pudieron cargar los productos')).toBeInTheDocument()
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
