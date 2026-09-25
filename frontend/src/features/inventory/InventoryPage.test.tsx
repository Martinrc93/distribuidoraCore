import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import InventoryPage from './InventoryPage'

function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
const inventory = { content: [{ id: 'product-1', product: 'Harina', stock: 0.25, lastMovement: 'PURCHASE', updated: '2026-09-24T10:00:00Z' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
const movements = { content: [{ id: 'movement-1', movementType: 'SALE', quantity: -1, reason: 'Pedido PED-001', referenceType: 'ORDER', referenceId: 'order-1', date: '2026-09-24T10:00:00Z' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
const depots = [{ id: 'depot-central', code: 'CENTRAL', name: 'Depósito Central', status: 'ACTIVE', isDefault: true }]
const depotBalances = { content: [{ productId: 'product-1', sku: 'SKU-1', product: 'Harina', stock: 0.25 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

function renderPage(authorities = ['ADMIN_ALL', 'STOCK_ADJUST']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><InventoryPage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('InventoryPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => vi.restoreAllMocks())

  it('loads balance and per-product movement history', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).includes('/movements') ? response(movements) : response(inventory))
    renderPage()

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: /ver movimientos de harina/i }))
    expect(await screen.findByText('Pedido PED-001')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/inventory/product-1/movements?page=0&size=20', expect.anything())
  })

  it('requires half-unit adjustment, confirms negative balance and refreshes inventory', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/inventory/depots') return response(depots)
      if (String(input).startsWith('/api/inventory/depots/') && String(input).includes('/balances?')) return response(depotBalances)
      if (String(input) === '/api/inventory?page=0&size=20' && !init?.method) return response(inventory)
      if (String(input) === '/api/inventory/product-1/adjustments' && init?.method === 'POST') return response({}, 204)
      if (String(input).includes('/movements')) return response(movements)
      return response(inventory)
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /ajustar stock de harina/i }))
    await user.type(screen.getByLabelText('Cantidad de ajuste'), '-0.25')
    await user.type(screen.getByLabelText('Motivo'), 'Corrección de conteo')
    await user.click(screen.getByRole('button', { name: /confirmar ajuste/i }))
    expect(await screen.findByText('La cantidad debe ser múltiplo de 0,5.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/inventory/product-1/adjustments', expect.objectContaining({ method: 'POST' }))

    await user.clear(screen.getByLabelText('Cantidad de ajuste'))
    await user.type(screen.getByLabelText('Cantidad de ajuste'), '-0.5')
    expect(screen.getByText(/el saldo quedará negativo/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /confirmar ajuste/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/inventory/product-1/adjustments', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ quantity: -0.5, reason: 'Corrección de conteo', depotId: 'depot-central' }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/inventory?page=0&size=20'] })
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
    expect(await screen.findByText('Ajuste de inventario registrado.')).toBeInTheDocument()
  })

  it('hides adjustments unless the user has the stock-adjust permission', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response(inventory))
    renderPage(['ORDER_CREATE'])

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /ajustar stock/i })).not.toBeInTheDocument()
  })
})
