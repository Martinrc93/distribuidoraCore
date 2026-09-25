import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DepotInventorySection from './DepotInventorySection'

function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
const depots = [
  { id: 'depot-central', code: 'CENTRAL', name: 'Depósito Central', status: 'ACTIVE', isDefault: true },
  { id: 'depot-north', code: 'NORTE', name: 'Depósito Norte', status: 'ACTIVE', isDefault: false },
]
const balances = { content: [{ productId: 'product-1', sku: 'SKU-1', product: 'Harina', stock: 5, updated: '2026-09-24T10:00:00Z' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

function renderSection(onAdjust = vi.fn(), authorities = ['ADMIN_ALL', 'STOCK_ADJUST']) {
  function TestSection() {
    const [selectedDepotId, setSelectedDepotId] = useState('')
    return <DepotInventorySection selectedDepotId={selectedDepotId} onSelectedDepotChange={setSelectedDepotId} onAdjust={onAdjust} />
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><TestSection /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('DepotInventorySection', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => vi.restoreAllMocks())

  it('selects the default depot and loads its product balances', async () => {
    const fetchMock = vi.spyOn(global, 'fetch')
    fetchMock.mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/inventory/depots' && !init?.method) return response(depots)
      if (path.startsWith('/api/inventory/depots/') && path.includes('/balances?')) return response(balances)
      return response({}, 204)
    })
    renderSection()

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.getByLabelText('Depósito seleccionado')).toHaveValue('depot-central')
    expect(fetchMock).toHaveBeenCalledWith('/api/inventory/depots/depot-central/balances?page=0&size=20&search=', expect.anything())
    expect(screen.queryByRole('button', { name: /desactivar depósito central/i })).not.toBeInTheDocument()
  })

  it('creates a depot and refreshes the depot list', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch')
    fetchMock.mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/inventory/depots' && !init?.method) return response(depots)
      if (path.startsWith('/api/inventory/depots/') && path.includes('/balances?')) return response(balances)
      return response({}, 204)
    })
    const queryClient = renderSection()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /nuevo depósito/i }))
    await user.type(screen.getByLabelText('Código del depósito'), 'OESTE')
    await user.type(screen.getByLabelText('Nombre del depósito'), 'Depósito Oeste')
    await user.click(screen.getByRole('button', { name: /guardar depósito/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/inventory/depots', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ code: 'OESTE', name: 'Depósito Oeste' }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/inventory/depots'] })
  })

  it('transfers a positive half-unit amount from the visible source balance', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch')
    fetchMock.mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/inventory/depots' && !init?.method) return response(depots)
      if (path.startsWith('/api/inventory/depots/') && path.includes('/balances?')) return response(balances)
      return response({}, 204)
    })
    const queryClient = renderSection()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /transferir harina/i }))
    await user.selectOptions(screen.getByLabelText('Depósito de destino'), 'depot-north')
    await user.type(screen.getByLabelText('Cantidad a transferir'), '1.5')
    await user.type(screen.getByLabelText('Motivo de transferencia'), 'Reposición de sucursal')
    await user.click(screen.getByRole('button', { name: /confirmar transferencia/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/inventory/transfers', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ fromDepotId: 'depot-central', toDepotId: 'depot-north', productId: 'product-1', quantity: 1.5, reason: 'Reposición de sucursal' }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/inventory/product-1/movements?page=0&size=20'] })
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
  })

  it('rejects transfers above the visible source balance', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch')
    fetchMock.mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/inventory/depots' && !init?.method) return response(depots)
      if (path.startsWith('/api/inventory/depots/') && path.includes('/balances?')) return response(balances)
      return response({}, 204)
    })
    renderSection()

    await user.click(await screen.findByRole('button', { name: /transferir harina/i }))
    await user.selectOptions(screen.getByLabelText('Depósito de destino'), 'depot-north')
    await user.type(screen.getByLabelText('Cantidad a transferir'), '5.5')
    await user.type(screen.getByLabelText('Motivo de transferencia'), 'Reposición')
    await user.click(screen.getByRole('button', { name: /confirmar transferencia/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('La cantidad supera el saldo visible del depósito de origen (5).')
    expect(fetchMock).not.toHaveBeenCalledWith('/api/inventory/transfers', expect.objectContaining({ method: 'POST' }))
  })

  it('confirms deactivation of a non-default depot', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch')
    fetchMock.mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/inventory/depots' && !init?.method) return response(depots)
      if (path.startsWith('/api/inventory/depots/') && path.includes('/balances?')) return response(balances)
      return response({}, 204)
    })
    const queryClient = renderSection()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /desactivar depósito norte/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/inventory/depots/depot-north/status', expect.anything())
    await user.click(screen.getByRole('button', { name: /confirmar/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/inventory/depots/depot-north/status', expect.objectContaining({
      method: 'PATCH', body: JSON.stringify({ active: false }),
    })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/inventory/depots'] })
  })
})
