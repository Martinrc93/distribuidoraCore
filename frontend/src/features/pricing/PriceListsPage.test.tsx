import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PriceListsPage from './PriceListsPage'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const lists = { content: [
  { id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE', isDefault: true },
  { id: 'list-2', code: 'MINORISTA', name: 'Minorista', status: 'INACTIVE', isDefault: false },
], page: 0, size: 20, totalElements: 2, totalPages: 1 }
const prices = { content: [{ priceListId: 'list-1', productId: 'product-1', sku: 'SKU-1', name: 'Harina', price: 150.5 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

function renderPage(authorities = ['ADMIN_ALL']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><PriceListsPage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('PriceListsPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => { vi.restoreAllMocks() })

  it('creates a named price list and refreshes the list query', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20' && !init?.method) return response(lists)
      if (String(input) === '/api/pricing/lists' && init?.method === 'POST') return response({ id: 'list-3' }, 201)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /nueva lista/i }))
    await user.type(screen.getByLabelText('Código'), 'PROMO')
    await user.type(screen.getByLabelText('Nombre de lista'), 'Promoción')
    await user.click(screen.getByRole('button', { name: /guardar lista/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ code: 'PROMO', name: 'Promoción' }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/pricing/lists?page=0&size=20'] })
    expect(await screen.findByText('Lista creada correctamente.')).toBeInTheDocument()
  })

  it('edits a selected product price using the list-specific endpoint', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1' && init?.method === 'PUT') return response({}, 204)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /editar precio de harina/i }))
    await user.clear(screen.getByLabelText('Precio de Harina'))
    await user.type(screen.getByLabelText('Precio de Harina'), '175.25')
    await user.click(screen.getByRole('button', { name: /guardar precio/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ price: 175.25 }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/pricing/lists/list-1/prices?page=0&size=20'] })
  })

  it('confirms list deactivation and hides pricing mutations from sellers', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20' && !init?.method) return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      return response({}, 204)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /desactivar mayorista/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/pricing/lists/list-1/status', expect.anything())
    await user.click(screen.getByRole('button', { name: /confirmar/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/status', expect.objectContaining({
      method: 'PATCH', body: JSON.stringify({ status: 'INACTIVE' }),
    })))

    cleanup()
    vi.restoreAllMocks()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      return response({})
    })
    renderPage(['ORDER_CREATE'])
    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nueva lista/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /editar precio/i })).not.toBeInTheDocument()
  })
})
