import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PaymentsPage from './PaymentsPage'

function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
const payments = { content: [{ id: 'payment-1', customer: 'Almacén Norte', sale: 'VEN-001', amount: 100, method: 'BANK_TRANSFER', transferReference: 'TR-123', date: '2026-09-24T10:00:00Z' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
const customers = { content: [{ id: 'customer-1', name: 'Almacén Norte', balance: 200 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

function renderPage(authorities = ['ADMIN_ALL', 'SALE_PAYMENT']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter><PaymentsPage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('PaymentsPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => vi.restoreAllMocks())

  it('shows payment transfer references returned by the backend', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).startsWith('/api/payments') ? response(payments) : response(customers))
    renderPage(['ORDER_CREATE'])

    expect(await screen.findByText('VEN-001')).toBeInTheDocument()
    expect(screen.getByText('TR-123')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /registrar pago/i })).not.toBeInTheDocument()
  })

  it('registers FIFO account payment with an optional transfer reference', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input).startsWith('/api/payments')) return response(payments)
      if (String(input).startsWith('/api/customers?')) return response(customers)
      if (String(input) === '/api/customers/customer-1/account-payments' && init?.method === 'POST') return response({
        customerId: 'customer-1', received: 50, balanceBefore: 200, balanceAfter: 150, allocationMode: 'FIFO', allocations: [{ saleId: 'sale-1', saleNumber: 'VEN-001', paymentId: 'payment-2', amount: 50 }],
      }, 201)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /registrar pago/i }))
    await user.selectOptions(screen.getByLabelText('Cliente del pago'), 'customer-1')
    await user.selectOptions(screen.getByLabelText('Medio del pago'), 'BANK_TRANSFER')
    expect(screen.getByLabelText('Referencia de transferencia')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Importe a registrar'), '50')
    await user.type(screen.getByLabelText('Referencia de transferencia'), 'TR-456')
    await user.click(screen.getByRole('button', { name: /guardar pago/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/customers/customer-1/account-payments', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ amount: 50, method: 'BANK_TRANSFER', transferReference: 'TR-456' }),
    })))
    expect(await screen.findByRole('status')).toHaveTextContent(/Pago aplicado por FIFO/)
    expect(screen.getAllByText('VEN-001').length).toBeGreaterThan(0)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/api/payments?page=0&size=20&search='] })
  })
})
