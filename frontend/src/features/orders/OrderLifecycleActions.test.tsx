import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderLifecycleActions from './OrderLifecycleActions'

function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function response(body?: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
function renderActions(authorities = ['ADMIN_ALL', 'SALE_DELIVER'], onChanged = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><OrderLifecycleActions orderId="order-1" orderNumber="PED-001" orderStatus="CONFIRMED" saleBalance={100} onChanged={onChanged} /></MemoryRouter></QueryClientProvider>)
  return { queryClient, onChanged }
}

describe('OrderLifecycleActions', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })
  beforeEach(() => { sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL', 'SALE_DELIVER'])) })

  it('validates failed delivery and records optional transfer collection on delivered attempt', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => String(input) === '/api/orders/order-1/delivery-attempts' && init?.method === 'POST' ? response(undefined, 204) : response({}))
    const { onChanged } = renderActions()

    await user.click(screen.getByRole('button', { name: /registrar entrega/i }))
    await user.selectOptions(screen.getByLabelText('Resultado de entrega'), 'FAILED')
    await user.click(screen.getByRole('button', { name: /confirmar intento/i }))
    expect(await screen.findByText('Agregá una observación para un intento fallido.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/orders/order-1/delivery-attempts', expect.objectContaining({ method: 'POST' }))

    await user.selectOptions(screen.getByLabelText('Resultado de entrega'), 'DELIVERED')
    await user.click(screen.getByRole('button', { name: /agregar cobro/i }))
    await user.selectOptions(screen.getByLabelText('Medio de cobro'), 'BANK_TRANSFER')
    await user.type(screen.getByLabelText('Importe cobrado'), '25')
    await user.type(screen.getByLabelText('Referencia de transferencia'), 'TR-DEL-1')
    await user.click(screen.getByRole('button', { name: /confirmar intento/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1/delivery-attempts', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ result: 'DELIVERED', observation: null, payments: [{ method: 'BANK_TRANSFER', amount: 25 }], transferReference: 'TR-DEL-1' }),
    })))
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('confirms cancellation before calling the order cancellation endpoint', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => String(input) === '/api/orders/order-1/cancel' && init?.method === 'POST' ? response(undefined, 204) : response({}))
    const { onChanged } = renderActions()

    await user.click(screen.getByRole('button', { name: /cancelar pedido/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/orders/order-1/cancel', expect.anything())
    await user.click(screen.getByRole('button', { name: /confirmar cancelación/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1/cancel', expect.objectContaining({ method: 'POST' })))
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('requests and displays notification status and downloads authenticated PDFs', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/orders/order-1/notifications' && init?.method === 'POST') return response({ requestId: 'request-1', status: 'QUEUED' }, 201)
      if (path === '/api/orders/order-1/notifications/request-1') return response({ requestId: 'request-1', status: 'SENT', attemptCount: 1, requestedAt: '2026-09-24T10:00:00Z', sentAt: '2026-09-24T10:01:00Z', lastError: null })
      if (path.includes('/documents/')) return Promise.resolve(new Response(new Blob(['pdf'], { type: 'application/pdf' }), { status: 200 }))
      return response({})
    })
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:ticket') })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    renderActions()

    await user.click(screen.getByRole('button', { name: /descargar ticket/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1/documents/ticket', expect.anything()))
    await user.click(screen.getByRole('button', { name: /compartir comprobante/i }))
    await user.selectOptions(screen.getByLabelText('Canal de envío'), 'WHATSAPP')
    await user.type(screen.getByLabelText('Destinatario'), '+5491112345678')
    await user.selectOptions(screen.getByLabelText('Formato de comprobante'), 'TICKET')
    await user.click(screen.getByRole('button', { name: /solicitar envío/i }))

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([input]) => String(input) === '/api/orders/order-1/notifications')
      expect(call).toBeDefined()
      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ channel: 'WHATSAPP', recipient: '+5491112345678', format: 'TICKET' })
      expect(JSON.parse(String(call?.[1]?.body)).idempotencyKey).toBeTruthy()
    })
    await user.click(screen.getByRole('button', { name: /consultar estado/i }))
    expect(await screen.findByText('Enviado')).toBeInTheDocument()
  })
})
