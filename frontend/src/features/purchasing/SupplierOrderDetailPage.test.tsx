import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SupplierOrderDetailPage from './SupplierOrderDetailPage'

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }))
const paged = (content: unknown[]) => ({ content, page: 0, size: 100, totalPages: 1, totalElements: content.length })
const supplier = { id: 'supplier-a', name: 'Proveedor Norte' }
const product = { id: 'product-a', name: 'Harina', cost: 25, status: 'ACTIVE' }
const detail = (status: string) => ({
  order: { id: 'order-1', number: 'PRV-00000003', supplierId: supplier.id, supplier: supplier.name, date: '2026-11-10', total: 40, status },
  items: [{ productId: product.id, productName: product.name, quantity: 2, unitCost: 20, lineTotal: 40, status: 'ACTIVE', currentCost: product.cost }],
})

function renderDetail() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/supplier-orders/order-1']}>
    <Routes><Route path="/supplier-orders/:orderId" element={<SupplierOrderDetailPage />} /></Routes>
  </MemoryRouter></QueryClientProvider>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('Supplier order detail', () => {
  it('confirms a pending purchase with Enter after explaining that stock will increase', async () => {
    let status = 'PENDING'
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/supplier-orders/order-1/confirm' && init?.method === 'POST') { status = 'CONFIRMED'; return json(detail(status)) }
      return json(detail(status))
    })
    const user = userEvent.setup()
    renderDetail()

    expect(await screen.findByText('Pendiente')).toBeInTheDocument()
    screen.getByRole('button', { name: 'Confirmar compra' }).focus()
    await user.keyboard('{Enter}')
    const dialog = await screen.findByRole('dialog', { name: '¿Confirmar la compra PRV-00000003?' })
    expect(dialog).toHaveTextContent('se van a sumar al stock')
    expect(within(dialog).getByRole('button', { name: 'Aceptar' })).toHaveFocus()
    expect(fetch).not.toHaveBeenCalledWith('/api/supplier-orders/order-1/confirm', expect.objectContaining({ method: 'POST' }))
    await user.keyboard('{Enter}')
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/supplier-orders/order-1/confirm', expect.objectContaining({ method: 'POST' })))
    expect(await screen.findByText('Compra PRV-00000003 confirmada: las cantidades se sumaron al stock.')).toBeInTheDocument()
    expect(await screen.findByText('Confirmada')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirmar compra' })).not.toBeInTheDocument()
  })

  it('cancels a confirmed purchase warning that stock will be reduced', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(() => json(detail('CONFIRMED')))
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Anular compra' }))
    const dialog = await screen.findByRole('dialog', { name: '¿Anular la compra PRV-00000003?' })
    expect(dialog).toHaveTextContent('Se van a descontar del stock')
    expect(within(dialog).getByRole('button', { name: 'Volver' })).toHaveFocus()
    await user.click(within(dialog).getByRole('button', { name: 'Anular compra' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/supplier-orders/order-1/cancel', expect.objectContaining({ method: 'POST' })))
    expect(await screen.findByText('Compra PRV-00000003 anulada: se descontaron sus cantidades del stock.')).toBeInTheDocument()
  })

  it('edits quantities in half units and preserves the recorded unit cost', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path.startsWith('/api/suppliers')) return json(paged([supplier]))
      if (path.startsWith('/api/products')) return json(paged([product]))
      if (path.includes('/last-order')) return json({ available: false })
      return json(detail('CONFIRMED'))
    })
    const user = userEvent.setup()
    renderDetail()

    const edit = await screen.findByRole('button', { name: 'Editar compra' })
    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['Producto', 'Cantidad'])
    expect(screen.queryByText(/Total estimado/)).not.toBeInTheDocument()
    await user.click(edit)
    expect(await screen.findByText(/el stock se ajusta por la diferencia/)).toBeInTheDocument()
    const quantity = screen.getByLabelText('Cantidad de Harina')
    await user.clear(quantity)
    await user.type(quantity, '2,25')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByText(/múltiplos de 0,5/)).toBeInTheDocument()
    await user.clear(quantity)
    await user.type(quantity, '3,5')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/supplier-orders/order-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ supplierId: supplier.id, orderDate: '2026-11-10', lines: [{ productId: product.id, quantity: 3.5, unitCost: 20 }] }),
    })))
    expect(await screen.findByText('Compra PRV-00000003 actualizada: el stock se ajustó por la diferencia.')).toBeInTheDocument()
  })
})
