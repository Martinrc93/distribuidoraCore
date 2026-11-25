import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SalesPage from './SalesPage'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const sale = { id: 'sale-1', number: 'SAL-1', customer: 'Kiosco Centro', customerId: 'client-1', accountCustomerId: 'reseller-1',
  account: 'Distribuidora Sur', seller: 'Lucía', total: 100, paid: 0, balance: 100, status: 'DELIVERED', date: '2026-11-18T12:00:00Z' }
const detail = {
  order: { id: 'order-1', number: '000300001', customer: 'Kiosco Centro', customerId: 'client-1', status: 'DELIVERED', total: 100,
    date: '2026-11-18T12:00:00Z', accountCustomerId: 'reseller-1', accountCustomer: 'Distribuidora Sur', accountKind: 'RESELLER' },
  sale: { id: 'sale-1', number: 'SAL-1', status: 'DELIVERED', total: 100, paid: 0, balance: 100 },
  payments: [],
  saleItems: [{ saleItemId: 'item-1', productId: 'product-1', productName: 'Harina', quantity: 1, returnedQuantity: 0, returnableQuantity: 1 }],
}

function renderPage(entry = '/sales') {
  const paths: string[] = []
  vi.spyOn(global, 'fetch').mockImplementation((input) => {
    const path = String(input)
    paths.push(path)
    if (path === '/api/sales/filter-options') return response({ customers: [], sellers: [] })
    if (path === '/api/sales/sale-1') return response(detail)
    return response({ content: [sale], page: 0, size: 20, totalElements: 1, totalPages: 1 })
  })
  sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[entry]}><SalesPage /></MemoryRouter></QueryClientProvider>)
  return paths
}

describe('Sales of resellers', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('lists the debt of a reseller account and blocks returns of its sales', async () => {
    const paths = renderPage('/sales?accountCustomerId=reseller-1&pendingBalance=true')
    const row = await screen.findByRole('row', { name: /SAL-1/ })
    expect(screen.getByRole('columnheader', { name: 'Vendedor / Revendedor' })).toBeInTheDocument()
    expect(within(row).getByRole('cell', { name: 'Kiosco Centro' })).toHaveTextContent(/^Kiosco Centro$/)
    expect(within(row).getByRole('cell', { name: 'Distribuidora Sur' })).toBeInTheDocument()
    expect(within(row).queryByText('Lucía')).not.toBeInTheDocument()
    expect(screen.queryByText('Cuenta: Distribuidora Sur')).not.toBeInTheDocument()
    await waitFor(() => expect(paths.some(path => path.startsWith('/api/sales?') && path.includes('accountCustomerId=reseller-1'))).toBe(true))
    expect(screen.getByRole('status')).toHaveTextContent('Ventas a cargo de la cuenta de Distribuidora Sur')
    await userEvent.setup().click(screen.getByRole('button', { name: /^Ver venta/ }))
    expect(await screen.findByText('Las ventas a cargo de un revendedor no admiten devoluciones.')).toBeInTheDocument()
    expect(screen.getByText('Distribuidora Sur (revendedor)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar devolución' })).not.toBeInTheDocument()
  })
})
