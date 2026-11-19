import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import OrderCreatePage from './OrderCreatePage'
import { DeliveryAttemptDialog } from './DeliveryAttemptDialog'
import { selectEntity } from '../../test/selectEntity'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const page = (content: unknown[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 })
const customers = page([{ id: 'client-1', name: 'Kiosco Centro', priceListId: 'list-1', sellerId: 'seller-1', seller: 'Lucía', balance: 0,
  kind: 'REGULAR', resellerId: 'reseller-1', reseller: 'Distribuidora Sur' }])

function mockApi() {
  const paths: string[] = []
  vi.spyOn(global, 'fetch').mockImplementation((input) => {
    const path = String(input)
    paths.push(path)
    if (path === '/api/customers/reseller-1/account-balance') return response({ id: 'reseller-1', name: 'Distribuidora Sur', balance: 800, status: 'ACTIVE' })
    if (path.endsWith('/last-order')) return response({ available: false })
    if (path.startsWith('/api/customers')) return response(customers)
    if (path.startsWith('/api/sellers')) return response(page([{ id: 'seller-1', displayName: 'Lucía', email: 'lucia@example.test' }]))
    if (path.startsWith('/api/products')) return response(page([{ id: 'product-1', name: 'Harina', status: 'ACTIVE' }]))
    if (path.startsWith('/api/pricing/lists')) return response(page([{ id: 'list-1', code: 'GENERAL', name: 'General', status: 'ACTIVE' }]))
    if (path.startsWith('/api/pricing/resolve-batch')) return response([{ priceListId: 'list-1', priceListCode: 'GENERAL', productId: 'product-1', unitPrice: 100 }])
    return response({})
  })
  return paths
}

function renderOrderForm(authorities: string[]) {
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/orders/new']}><Routes><Route path="/orders/new" element={<OrderCreatePage />} /></Routes></MemoryRouter></QueryClientProvider>)
}

describe('Reseller orders', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('shows administrators the reseller account without a previous balance for the delivery note', async () => {
    mockApi()
    renderOrderForm(['ADMIN_ALL'])
    await selectEntity(userEvent.setup(), 'Cliente', 'Kiosco Centro')
    expect(await screen.findByText('Distribuidora Sur', { selector: 'strong' })).toBeInTheDocument()
    const account = screen.getByRole('region', { name: 'Cuenta corriente de Distribuidora Sur' })
    expect(await within(account).findByText(/800/)).toBeInTheDocument()
    expect(within(account).getByText(/El remito del cliente no incluye saldo anterior/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Importe para el remito')).not.toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Pago de deuda anterior' })).toHaveTextContent('Deuda anterior de Distribuidora Sur')
  })

  it('lets sellers load orders of reseller customers without collecting', async () => {
    const paths = mockApi()
    renderOrderForm(['ORDER_CREATE'])
    await selectEntity(userEvent.setup(), 'Cliente', 'Kiosco Centro')
    expect(await screen.findByText('Los cobros de cuentas de revendedor los registra un administrador.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Cobro al confirmar (opcional)' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Importe para el remito')).not.toBeInTheDocument()
    expect(paths).not.toContain('/api/customers/reseller-1/account-balance')
  })

  it('replaces delivery payments with the notice when the user cannot collect', () => {
    render(<DeliveryAttemptDialog mode="delivery" orderNumber="000300001" saleBalance={100}
      value={{ result: 'DELIVERED', observation: '', payments: [], transferReference: '', includePreviousDebt: false, previousDebtAmount: '' }}
      pending={false} error="" onChange={() => {}} onSubmit={() => {}} onClose={() => {}}
      collectionsNotice="Los cobros de cuentas de revendedor los registra un administrador." />)
    expect(screen.getByText('Los cobros de cuentas de revendedor los registra un administrador.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Agregar pago' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmar entrega' })).toBeEnabled()
  })
})
