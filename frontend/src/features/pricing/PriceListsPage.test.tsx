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
const prices = { content: [{ priceListId: 'list-1', productId: 'product-1', sku: 'SKU-1', name: 'Harina', price: 150.5, effectiveOn: '2026-09-24' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
function pageHistory(effectiveOn: string) {
  return { content: [{ effectiveOn, price: 150.5, recordedAt: '2026-09-24T12:00:00Z', updatedAt: '2026-09-24T12:00:00Z', scheduled: effectiveOn > '2026-09-24' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
}

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

  it('schedules a product price for its effective date', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1' && init?.method === 'PUT') return response({}, 204)
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /editar precio de harina/i }))
    await user.clear(screen.getByLabelText('Precio de Harina'))
    await user.type(screen.getByLabelText('Precio de Harina'), '180')
    await user.type(screen.getByLabelText('Fecha de vigencia'), '2026-10-01')
    await user.click(screen.getByRole('button', { name: /guardar precio/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ price: 180, effectiveOn: '2026-10-01' }),
    })))
  })

  it('shows price history and confirms cancellation of a scheduled entry', async () => {
    const user = userEvent.setup()
    const history = { content: [
      { effectiveOn: '2026-09-24', price: 150.5, recordedAt: '2026-09-24T12:00:00Z', updatedAt: '2026-09-24T12:00:00Z', scheduled: false },
      { effectiveOn: '2026-10-01', price: 180, recordedAt: '2026-09-24T13:00:00Z', updatedAt: '2026-09-24T13:00:00Z', scheduled: true },
    ], page: 0, size: 20, totalElements: 2, totalPages: 1 }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1/history?page=0&size=20') return response(history)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1/history/2026-10-01' && init?.method === 'DELETE') return response({}, 204)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /historial de harina/i }))
    expect(await screen.findByText('2026-10-01')).toBeInTheDocument()
    expect(screen.getByText('Programado')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /cancelar precio del 2026-10-01/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1/history/2026-10-01', expect.anything())
    await user.click(screen.getByRole('button', { name: /confirmar cancelación/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1/history/2026-10-01', expect.objectContaining({ method: 'DELETE' })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/pricing/lists/list-1/products/product-1/history?page=0&size=20'] })
  })

  it('pages through the selected product price history', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (path === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (path === '/api/pricing/lists/list-1/products/product-1/history?page=0&size=20') return response({ ...pageHistory('2026-09-24'), totalElements: 21, totalPages: 2 })
      if (path === '/api/pricing/lists/list-1/products/product-1/history?page=1&size=20') return response({ ...pageHistory('2026-10-01'), totalElements: 21, totalPages: 2 })
      return response({})
    })
    renderPage(['ORDER_CREATE'])

    await user.click(await screen.findByRole('button', { name: /historial de harina/i }))
    await screen.findByText(/Página 1 de 2/)
    expect(screen.getAllByText('2026-09-24')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Siguiente historial' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1/history?page=1&size=20', expect.anything()))
    expect(await screen.findByText('2026-10-01')).toBeInTheDocument()
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
