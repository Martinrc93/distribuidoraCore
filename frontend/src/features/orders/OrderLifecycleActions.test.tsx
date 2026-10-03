import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderLifecycleActions from './OrderLifecycleActions'

function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function response(body?: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
function renderActions(authorities = ['ADMIN_ALL', 'SALE_DELIVER'], onChanged = vi.fn(), previousDebtAvailable = 0) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><OrderLifecycleActions orderId="order-1" orderNumber="PED-001" orderStatus="CONFIRMED" saleBalance={100} previousDebtAvailable={previousDebtAvailable} onChanged={onChanged} /></MemoryRouter></QueryClientProvider>)
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
    await user.click(screen.getByRole('button', { name: /agregar pago/i }))
    await user.selectOptions(screen.getByLabelText('Medio de pago'), 'BANK_TRANSFER')
    await user.type(screen.getByLabelText('Importe del pago'), '25')
    await user.type(screen.getByLabelText('Número de transferencia'), 'TR-DEL-1')
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

  it('focuses delivery fields, restores the trigger on Escape and preserves the draft when reopened', async () => {
    const user = userEvent.setup()
    renderActions()
    const trigger = screen.getByRole('button', { name: 'Registrar entrega' })
    await user.click(trigger)
    expect(screen.getByLabelText('Resultado de entrega')).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')
    expect(screen.queryByLabelText('Observación')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Resultado de entrega'), 'FAILED')
    await user.type(screen.getByLabelText('Observación'), 'Entrega por la tarde')
    fireEvent(screen.getByRole('dialog', { name: 'Registrar intento de entrega' }), new Event('cancel', { cancelable: true }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(document.body.style.overflow).toBe('')
    await user.click(trigger)
    expect(screen.getByLabelText('Observación')).toHaveValue('Entrega por la tarde')
  })

  it('includes an editable previous debt, fills total or current sale and requires the transfer number inside the payment', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response(undefined, 204))
    renderActions(['SALE_DELIVER'], vi.fn(), 80)
    await user.click(screen.getByRole('button', { name: 'Registrar entrega' }))
    expect(screen.getByRole('heading', { name: 'Pago' })).toBeInTheDocument()
    expect(screen.queryByText(/opcional/i)).not.toBeInTheDocument()
    await user.click(screen.getByLabelText('Agregar deuda anterior'))
    await user.clear(screen.getByLabelText('Importe de deuda anterior'))
    await user.type(screen.getByLabelText('Importe de deuda anterior'), '90')
    await user.click(screen.getByRole('button', { name: 'Confirmar intento' }))
    expect(screen.getByRole('alert')).toHaveTextContent('La deuda anterior debe ser mayor a cero y no superar')
    expect(fetchMock).not.toHaveBeenCalled()
    await user.clear(screen.getByLabelText('Importe de deuda anterior'))
    await user.type(screen.getByLabelText('Importe de deuda anterior'), '40,5')
    await user.click(screen.getByRole('button', { name: 'Pagar total' }))
    expect(screen.getByLabelText('Importe del pago')).toHaveValue('140,5')
    await user.click(screen.getByRole('button', { name: 'Pagar solo esta venta' }))
    expect(screen.getByLabelText('Importe del pago')).toHaveValue('100')
    await user.click(screen.getByRole('button', { name: 'Pagar total' }))
    await user.selectOptions(screen.getByLabelText('Medio de pago'), 'BANK_TRANSFER')
    const reference = screen.getByLabelText('Número de transferencia')
    expect(reference.closest('.delivery-attempt-payment')).toBe(screen.getByLabelText('Importe del pago').closest('.delivery-attempt-payment'))
    await user.click(screen.getByRole('button', { name: 'Confirmar intento' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Ingresa el número de transferencia.')
    expect(fetchMock).not.toHaveBeenCalled()
    await user.type(reference, 'TR-ANTERIOR')
    await user.click(screen.getByRole('button', { name: 'Confirmar intento' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1/delivery-attempts', expect.objectContaining({
      body: JSON.stringify({ result: 'DELIVERED', observation: null, payments: [{ method: 'BANK_TRANSFER', amount: 140.5 }], transferReference: 'TR-ANTERIOR', previousDebtAmount: 40.5 }),
    })))
  })

  it('removes the last collection and clears collections when switching to a failed delivery', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response(undefined, 204))
    renderActions()
    await user.click(screen.getByRole('button', { name: 'Registrar entrega' }))
    await user.click(screen.getByRole('button', { name: 'Agregar pago' }))
    await user.selectOptions(screen.getByLabelText('Medio de pago'), 'BANK_TRANSFER')
    await user.type(screen.getByLabelText('Número de transferencia'), 'TR-1')
    await user.click(screen.getByRole('button', { name: 'Quitar pago 1' }))
    expect(screen.queryByLabelText('Importe del pago')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Número de transferencia')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Agregar pago' }))
    await user.type(screen.getByLabelText('Importe del pago'), '150')
    await user.click(screen.getByRole('button', { name: 'Confirmar intento' }))
    expect(screen.getByRole('alert')).toHaveTextContent('El pago supera el saldo pendiente')
    expect(fetchMock).not.toHaveBeenCalled()
    await user.selectOptions(screen.getByLabelText('Resultado de entrega'), 'FAILED')
    await user.type(screen.getByLabelText('Observación'), 'Cliente ausente')
    await user.click(screen.getByRole('button', { name: 'Confirmar intento' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1/delivery-attempts', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ result: 'FAILED', observation: 'Cliente ausente' }),
    })))
  })

  it('blocks editing and closing while saving and keeps collection data after an API error', async () => {
    const user = userEvent.setup()
    let finish!: (value: Response) => void
    vi.spyOn(global, 'fetch').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    renderActions()
    await user.click(screen.getByRole('button', { name: 'Registrar entrega' }))
    await user.click(screen.getByRole('button', { name: 'Agregar pago' }))
    await user.type(screen.getByLabelText('Importe del pago'), '25')
    await user.click(screen.getByRole('button', { name: 'Confirmar intento' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByLabelText('Importe del pago')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cerrar registro de entrega' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Quitar pago 1' })).toBeDisabled()
    fireEvent(dialog, new Event('cancel', { cancelable: true }))
    expect(dialog).toBeInTheDocument()
    finish(new Response(JSON.stringify({ detail: 'No se pudo registrar la entrega.' }), { status: 409, headers: { 'Content-Type': 'application/json' } }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo registrar la entrega.')
    expect(screen.getByLabelText('Importe del pago')).toHaveValue('25')
    expect(screen.getByRole('button', { name: 'Confirmar intento' })).toBeEnabled()
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
