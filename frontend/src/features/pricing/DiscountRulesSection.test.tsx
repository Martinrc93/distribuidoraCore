import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DiscountRulesSection from './DiscountRulesSection'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const page = <T,>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })
const rules = page([{ id: 'rule-1', code: 'LINE10', description: 'Descuento harinas', kind: 'LINE', percent: 10, customerId: 'customer-1', customerName: 'Almacén Norte', priceListId: 'list-1', priceListCode: 'MAYORISTA', productId: 'product-1', productName: 'Harina', priority: 5, status: 'ACTIVE', validFrom: '2026-09-24', validUntil: null }])

function renderSection(authorities = ['ADMIN_ALL']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><DiscountRulesSection /></QueryClientProvider>)
  return queryClient
}

describe('DiscountRulesSection', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => { vi.restoreAllMocks() })

  it('creates a line discount with selected customer, price list, and product', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/pricing/discount-rules?page=0&size=20') return response(page([]))
      if (path === '/api/customers?page=0&size=100') return response(page([{ id: 'customer-1', name: 'Almacén Norte', status: 'ACTIVE' }]))
      if (path === '/api/pricing/lists?page=0&size=100') return response(page([{ id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }]))
      if (path === '/api/products?page=0&size=100') return response(page([{ id: 'product-1', sku: 'SKU-1', name: 'Harina', status: 'ACTIVE' }]))
      if (path === '/api/pricing/discount-rules' && init?.method === 'POST') return response({ id: 'rule-2' }, 201)
      return response({})
    })
    const queryClient = renderSection()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /nueva regla/i }))
    await user.type(screen.getByLabelText('Código'), 'CLIENTE_LINEA10')
    await user.type(screen.getByLabelText('Descripción'), '10% para este cliente')
    await user.type(screen.getByLabelText('Porcentaje'), '10')
    await user.selectOptions(screen.getByLabelText('Cliente (opcional)'), 'customer-1')
    await user.selectOptions(screen.getByLabelText('Lista de precios (opcional)'), 'list-1')
    await user.selectOptions(screen.getByLabelText('Producto'), 'product-1')
    await user.type(screen.getByLabelText('Prioridad'), '5')
    await user.click(screen.getByRole('button', { name: /guardar regla/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/discount-rules', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ code: 'CLIENTE_LINEA10', description: '10% para este cliente', kind: 'LINE', percent: 10, customerId: 'customer-1', priceListId: 'list-1', productId: 'product-1', validFrom: null, validUntil: null, priority: 5 }),
    })))
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
  })

  it('requires a product for a line rule and never sends an invalid request', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/pricing/discount-rules?page=0&size=20') return response(page([]))
      if (String(input) === '/api/customers?page=0&size=100') return response(page([]))
      if (String(input) === '/api/pricing/lists?page=0&size=100') return response(page([]))
      if (String(input) === '/api/products?page=0&size=100') return response(page([]))
      return response({})
    })
    renderSection()

    await user.click(await screen.findByRole('button', { name: /nueva regla/i }))
    await user.type(screen.getByLabelText('Código'), 'SINPRODUCTO')
    await user.type(screen.getByLabelText('Descripción'), 'Regla incompleta')
    await user.type(screen.getByLabelText('Porcentaje'), '5')
    await user.click(screen.getByRole('button', { name: /guardar regla/i }))

    expect(screen.getByLabelText('Producto')).toBeRequired()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/pricing/discount-rules', expect.objectContaining({ method: 'POST' }))
  })

  it('updates a rule and confirms status changes only for administrators', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/pricing/discount-rules?page=0&size=20') return response(rules)
      if (path === '/api/customers?page=0&size=100') return response(page([{ id: 'customer-1', name: 'Almacén Norte', status: 'ACTIVE' }]))
      if (path === '/api/pricing/lists?page=0&size=100') return response(page([{ id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }]))
      if (path === '/api/products?page=0&size=100') return response(page([{ id: 'product-1', sku: 'SKU-1', name: 'Harina', status: 'ACTIVE' }]))
      if (path === '/api/pricing/discount-rules/rule-1' && init?.method === 'PUT') return response({}, 204)
      if (path === '/api/pricing/discount-rules/rule-1/status' && init?.method === 'PATCH') return response({}, 204)
      return response({})
    })
    renderSection()

    await user.click(await screen.findByRole('button', { name: /editar regla line10/i }))
    await user.clear(screen.getByLabelText('Descripción'))
    await user.type(screen.getByLabelText('Descripción'), 'Descuento actualizado')
    await user.click(screen.getByRole('button', { name: /guardar regla/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/discount-rules/rule-1', expect.objectContaining({ method: 'PUT' })))

    await user.click(screen.getByRole('button', { name: /desactivar regla line10/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/pricing/discount-rules/rule-1/status', expect.anything())
    await user.click(screen.getByRole('button', { name: /confirmar cambio/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/discount-rules/rule-1/status', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ status: 'INACTIVE' }) })))

    cleanup()
    vi.restoreAllMocks()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/pricing/discount-rules?page=0&size=20') return response(rules)
      if (String(input) === '/api/customers?page=0&size=100') return response(page([]))
      if (String(input) === '/api/pricing/lists?page=0&size=100') return response(page([]))
      if (String(input) === '/api/products?page=0&size=100') return response(page([]))
      return response({})
    })
    renderSection(['ORDER_CREATE'])
    expect(await screen.findByText('Descuento harinas')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nueva regla/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /editar regla/i })).not.toBeInTheDocument()
  })

  it('pages through the discount rules returned by the backend', async () => {
    const user = userEvent.setup()
    const secondRule = { ...rules.content[0], id: 'rule-2', code: 'ORDER5', description: 'Descuento de pedido', kind: 'ORDER', productId: null, productName: null, sku: null, priority: 1 }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/pricing/discount-rules?page=0&size=20') return response({ ...rules, totalElements: 21, totalPages: 2 })
      if (path === '/api/pricing/discount-rules?page=1&size=20') return response({ ...page([secondRule]), page: 1, totalElements: 21, totalPages: 2 })
      if (path === '/api/customers?page=0&size=100') return response(page([{ id: 'customer-1', name: 'Almacén Norte', status: 'ACTIVE' }]))
      if (path === '/api/pricing/lists?page=0&size=100') return response(page([{ id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }]))
      if (path === '/api/products?page=0&size=100') return response(page([{ id: 'product-1', sku: 'SKU-1', name: 'Harina', status: 'ACTIVE' }]))
      return response({})
    })
    renderSection()

    expect(await screen.findByText('Descuento harinas')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Siguiente reglas' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/discount-rules?page=1&size=20', expect.anything()))
    expect(await screen.findByText('Descuento de pedido')).toBeInTheDocument()
  })

  it('retries the rules query after a read error', async () => {
    const user = userEvent.setup()
    let attempts = 0
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/pricing/discount-rules?page=0&size=20') {
        attempts += 1
        return attempts === 1 ? response({ detail: 'API temporalmente no disponible' }, 503) : response(page([]))
      }
      if (path === '/api/customers?page=0&size=100') return response(page([]))
      if (path === '/api/pricing/lists?page=0&size=100') return response(page([]))
      if (path === '/api/products?page=0&size=100') return response(page([]))
      return response({})
    })
    renderSection()

    expect(await screen.findByRole('button', { name: 'Reintentar reglas' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar reglas' }))

    expect(await screen.findByText('Todavía no hay reglas')).toBeInTheDocument()
    expect(attempts).toBe(2)
  })
})
