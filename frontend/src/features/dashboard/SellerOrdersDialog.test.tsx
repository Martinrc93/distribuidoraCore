import { useState } from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SellerOrdersDialog, type SellerOrderSelection } from './SellerOrdersDialog'

const selection: SellerOrderSelection = { sellerId: 'seller-1', seller: 'Lucía', dateMin: '2026-09-01', dateMax: '2026-09-30' }
const order = { id: 'order-1', number: 'PED-001', customer: 'Almacén Norte', date: '2026-09-01T03:00:00Z', deliveredAt: '2026-09-03T03:00:00Z', total: 100, paid: 40, accountBalance: 60, payments: [{ method: 'CASH', amount: 20 }, { method: 'BANK_TRANSFER', amount: 20 }] }
const page = { content: [order], page: 0, size: 20, totalElements: 21, totalPages: 2 }
const response = (value: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(value) } as Response)

function Harness({ seller = selection }: { seller?: SellerOrderSelection }) {
  const [open, setOpen] = useState(false)
  return <><button onClick={() => setOpen(true)}>Ver vendedor</button>{open && <SellerOrdersDialog selection={seller} onClose={() => setOpen(false)} />}</>
}

function show(seller = selection, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  render(<QueryClientProvider client={client}><Harness seller={seller} /></QueryClientProvider>)
  const trigger = screen.getByRole('button', { name: 'Ver vendedor' })
  trigger.focus()
  fireEvent.click(trigger)
  return trigger
}

const methods = ['showModal', 'close'] as const
const descriptors = methods.map((method) => Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, method))

describe('SellerOrdersDialog', () => {
  beforeEach(() => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } })
  })
  afterEach(() => {
    cleanup(); vi.restoreAllMocks()
    methods.forEach((method, index) => {
      const descriptor = descriptors[index]
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, method, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, method)
    })
  })

  it('shows mixed payments and current debt, paginates and restores focus after closing', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => response(String(input).includes('page=1')
      ? { ...page, page: 1, content: [{ ...order, id: 'order-2', number: 'PED-002', payments: [], deliveredAt: null }] } : page))
    const trigger = show()
    const dialog = screen.getByRole('dialog', { name: 'Pedidos entregados · Lucía' })
    expect(screen.getByRole('button', { name: 'Cerrar detalle' })).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')
    await screen.findByText('PED-001')
    expect(within(dialog).getByText('Almacén Norte')).toBeInTheDocument()
    expect(within(dialog).getByText('03/09/2026')).toBeInTheDocument()
    expect(dialog.querySelector('[data-label="Pagado en efectivo"]')).toHaveTextContent('20,00')
    expect(dialog.querySelector('[data-label="Pagado en transferencia"]')).toHaveTextContent('20,00')
    expect(within(dialog).getByText(/60,00/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/dashboard/seller-orders?dateMin=2026-09-01&dateMax=2026-09-30&page=0&size=20&sellerId=seller-1', expect.anything())
    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    await screen.findByText('PED-002')
    expect(screen.queryByText('PED-001')).not.toBeInTheDocument()
    expect(dialog.querySelector('[data-label="Pagado en efectivo"]')).toHaveTextContent('0,00')
    expect(dialog.querySelector('[data-label="Pagado en transferencia"]')).toHaveTextContent('0,00')
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar detalle' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(document.body.style.overflow).toBe('')
  })

  it('queries unassigned orders and keeps an empty result inside the table', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({ ...page, content: [], totalElements: 0, totalPages: 0 }))
    show({ ...selection, sellerId: null, seller: 'Sin asignar' })
    await screen.findByText('No hay pedidos entregados para este vendedor en el período seleccionado.')
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('&unassigned=true'), expect.anything())
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled()
  })

  it('retries errors while retaining the modal and supports Escape dismissal', async () => {
    vi.spyOn(global, 'fetch').mockRejectedValueOnce(new Error('Error de conexión')).mockImplementation(() => response(page))
    const trigger = show()
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    await screen.findByText('PED-001')
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: true, cancelable: true }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })

  it('shows a missing endpoint immediately even when the client allows automatic retries', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockResolvedValueOnce({
      ok: false, status: 404, json: () => Promise.resolve({ message: 'Endpoint no disponible' }),
    } as Response).mockImplementation(() => response(page))
    show(selection, new QueryClient())
    await screen.findByRole('alert')
    expect(screen.queryByText('Cargando pedidos entregados')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    await screen.findByText('PED-001')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
