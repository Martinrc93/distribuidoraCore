import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProductsPage from './ProductsPage'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
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
  content: [{
    id: 'product-1', sku: 'SKU-1', name: 'Harina', category: 'Almacén', categoryId: 'category-1',
    brandId: 'brand-1', presentation: 'Bolsa', cost: 100.5, stock: 4, status: 'ACTIVE',
  }],
  page: 0, size: 20, totalElements: 1, totalPages: 1,
}

const lists = {
  content: [
    { id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' },
    { id: 'list-2', code: 'MINORISTA', name: 'Minorista', status: 'INACTIVE' },
  ],
  page: 0, size: 20, totalElements: 2, totalPages: 1,
}

function catalogResponse(input: RequestInfo | URL) {
  const path = String(input)
  if (path.startsWith('/api/products')) return response(products)
  if (path.startsWith('/api/pricing/lists')) return response(lists)
  if (path.startsWith('/api/categories')) return response([{ id: 'category-1', name: 'Almacén', status: 'ACTIVE' }, { id: 'category-2', name: 'Bajas', status: 'INACTIVE' }])
  if (path.startsWith('/api/brands')) return response([{ id: 'brand-1', name: 'Molino Norte', status: 'ACTIVE' }, { id: 'brand-2', name: 'Archivada', status: 'INACTIVE' }])
  return response({})
}

describe('ProductsPage', () => {
  afterEach(() => {
    cleanup()
    sessionStorage.clear()
  })

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows no product-level price or general-price column', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage([])

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.queryByText('Lista general')).not.toBeInTheDocument()
    expect(screen.queryByText('$ 150,75')).not.toBeInTheDocument()
  })

  it('creates a product with a price for each active list and selected catalog ids', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response({ ...products, content: [] })
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input).startsWith('/api/categories')) return catalogResponse(input)
      if (String(input).startsWith('/api/brands')) return catalogResponse(input)
      if (String(input) === '/api/products' && init?.method === 'POST') return response({ id: 'new-product' }, 201)
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-2')
    await user.type(screen.getByLabelText('Nombre'), 'Arroz')
    await user.clear(screen.getByLabelText('Costo'))
    await user.type(screen.getByLabelText('Costo'), '12.5')
    await user.clear(screen.getByLabelText('Precio para MAYORISTA'))
    await user.type(screen.getByLabelText('Precio para MAYORISTA'), '20.75')
    await user.selectOptions(screen.getByLabelText('Asociar categoría'), 'category-1')
    await user.selectOptions(screen.getByLabelText('Marca'), 'brand-1')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        sku: 'SKU-2', name: 'Arroz', category: 'Almacén', presentation: 'Unidad', cost: 12.5,
        prices: [{ priceListId: 'list-1', price: 20.75 }], categoryId: 'category-1', brandId: 'brand-1',
      }),
    })))
    expect(await screen.findByText('Producto creado correctamente.')).toBeInTheDocument()
  })

  it('updates cost without prices when no active list price is affected', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      if (String(input) === '/api/products/product-1' && init?.method === 'PUT') return response({}, 204)
      return catalogResponse(input)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.clear(screen.getByLabelText('Costo'))
    await user.type(screen.getByLabelText('Costo'), '110')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products/product-1', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ sku: 'SKU-1', name: 'Harina', category: 'Almacén', presentation: 'Bolsa', cost: 110, categoryId: 'category-1', brandId: 'brand-1' }),
    })))
    expect(await screen.findByText('Producto actualizado correctamente.')).toBeInTheDocument()
  })

  it('requires replacement prices for only the lists returned by a cost conflict', async () => {
    const user = userEvent.setup()
    let updateAttempts = 0
    const affectedPriceLists = [{ priceListId: 'list-1', code: 'MAYORISTA', currentPrice: 120 }]
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      if (String(input) === '/api/products/product-1' && init?.method === 'PUT') {
        updateAttempts += 1
        return updateAttempts === 1
          ? response({ code: 'INVALID_PRODUCT_PRICES', detail: 'El costo supera precios vigentes', affectedPriceLists }, 400)
          : response({}, 204)
      }
      return catalogResponse(input)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.clear(screen.getByLabelText('Costo'))
    await user.type(screen.getByLabelText('Costo'), '130')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    expect(await screen.findByLabelText('Precio para MAYORISTA')).toBeInTheDocument()
    expect(screen.getByText('El costo supera precios vigentes')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Precio para MAYORISTA'), '125')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('El precio para MAYORISTA debe ser igual o mayor al nuevo costo.')).toBeInTheDocument()
    expect(updateAttempts).toBe(1)

    await user.clear(screen.getByLabelText('Precio para MAYORISTA'))
    await user.type(screen.getByLabelText('Precio para MAYORISTA'), '140')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products/product-1', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ sku: 'SKU-1', name: 'Harina', category: 'Almacén', presentation: 'Bolsa', cost: 130, prices: [{ priceListId: 'list-1', price: 140 }], categoryId: 'category-1', brandId: 'brand-1' }),
    })))
    expect(await screen.findByText('Producto actualizado correctamente.')).toBeInTheDocument()
  })

  it('requires an initial active-list price to be a valid amount not below cost', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response({ ...products, content: [] })
      return catalogResponse(input)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('SKU'), 'SKU-2')
    await user.type(screen.getByLabelText('Nombre'), 'Arroz')
    await user.type(screen.getByLabelText('Categoría'), 'Almacén')
    await user.clear(screen.getByLabelText('Costo'))
    await user.type(screen.getByLabelText('Costo'), '10')
    await user.clear(screen.getByLabelText('Precio para MAYORISTA'))
    await user.type(screen.getByLabelText('Precio para MAYORISTA'), '9')
    await user.click(screen.getByRole('button', { name: /guardar producto/i }))

    expect(await screen.findByText('El precio para MAYORISTA debe ser igual o mayor al costo.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/products', expect.objectContaining({ method: 'POST' }))
  })

  it('requires confirmation before changing product status and hides commands from sellers', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/products?page=0&size=20' && !init?.method) return response(products)
      return catalogResponse(input)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /desactivar producto/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/products/product-1/status', expect.anything())
    await user.click(screen.getByRole('button', { name: /cancelar/i }))

    cleanup()
    sessionStorage.setItem('distribuidora.accessToken', token(['ORDER_CREATE']))
    vi.restoreAllMocks()
    vi.spyOn(global, 'fetch').mockImplementation((input) => catalogResponse(input))
    renderPage(['ORDER_CREATE'])
    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo producto/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /editar/i })).not.toBeInTheDocument()
  })
})
