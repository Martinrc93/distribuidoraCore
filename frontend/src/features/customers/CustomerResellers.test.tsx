import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CustomersPage from './CustomersPage'
import CustomerDetailPage from './CustomerDetailPage'
import { selectEntity } from '../../test/selectEntity'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function renderPage(authorities: string[]) {
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter><CustomersPage /></MemoryRouter></QueryClientProvider>)
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })
const reseller = { id: 'reseller-1', name: 'Distribuidora Sur', seller: 'Lucía', balance: 500, status: 'ACTIVE', kind: 'RESELLER' }
const client = { id: 'client-1', name: 'Kiosco Centro', seller: 'Sin asignar', balance: 0, status: 'ACTIVE', kind: 'REGULAR', resellerId: 'reseller-1', reseller: 'Distribuidora Sur' }
const regular = { id: 'regular-1', name: 'Almacén Norte', seller: 'Ana', balance: 300, status: 'ACTIVE', kind: 'REGULAR' }

type Call = { path: string; method: string; body?: unknown }

function mockApi(overrides: (call: Call) => Promise<Response> | undefined = () => undefined) {
  const calls: Call[] = []
  vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
    const call = { path: String(input), method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined }
    calls.push(call)
    const custom = overrides(call)
    if (custom) return custom
    if (call.path === '/api/zones') return response([])
    if (call.path === '/api/customers/filter-options') return response({ sellers: [] })
    if (call.path.includes('kind=RESELLER')) return response(page([reseller]))
    if (call.path.startsWith('/api/customers?')) return response(page([reseller, client, regular]))
    return response(page([]))
  })
  return calls
}

describe('Customer resellers', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('shows only customer names in the customer column, with reseller actions reserved to administrators', async () => {
    mockApi()
    const user = userEvent.setup()
    renderPage(['ADMIN_ALL', 'RESELLER_MANAGE'])
    const resellerRow = (await screen.findByText('Distribuidora Sur', { selector: 'span' })).closest('tr')!
    expect(within(resellerRow).queryByText('Revendedor', { selector: '.badge, span' })).not.toBeInTheDocument()
    await user.click(within(resellerRow).getByLabelText('Más acciones de Distribuidora Sur'))
    expect(within(resellerRow).getByRole('button', { name: 'Revendedor de Distribuidora Sur' })).toBeInTheDocument()
    const clientRow = screen.getByText('Kiosco Centro').closest('tr')!
    expect(within(clientRow).queryByText('Cuenta: Distribuidora Sur')).not.toBeInTheDocument()
    await user.click(within(clientRow).getByLabelText('Más acciones de Kiosco Centro'))
    expect(within(clientRow).getByRole('button', { name: 'Revendedor de Kiosco Centro' })).toBeInTheDocument()
    const regularRow = screen.getByText('Almacén Norte').closest('tr')!
    await user.click(within(regularRow).getByLabelText('Más acciones de Almacén Norte'))
    expect(within(regularRow).getByRole('button', { name: 'Revendedor de Almacén Norte' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Buscar por revendedor' })).toBeInTheDocument()
  })

  it('filters the customers of a reseller', async () => {
    const calls = mockApi()
    renderPage(['ADMIN_ALL', 'RESELLER_MANAGE'])
    await screen.findByText('Kiosco Centro')
    await selectEntity(userEvent.setup(), 'Buscar por revendedor', 'Distribuidora Sur')
    await waitFor(() => expect(calls.some(call => call.path.startsWith('/api/customers?page=0') && call.path.includes('resellerId=reseller-1'))).toBe(true))
  })

  it('hides reseller actions without RESELLER_MANAGE and keeps reseller collections with administrators', async () => {
    mockApi()
    renderPage(['ADMIN_ALL'])
    await screen.findByText('Kiosco Centro')
    await userEvent.setup().click(await screen.findByLabelText('Más acciones de Kiosco Centro'))
    expect(screen.queryByRole('button', { name: /^Revendedor de/ })).not.toBeInTheDocument()
    cleanup()
    vi.restoreAllMocks()
    mockApi()
    renderPage(['ACCOUNT_SETTLE'])
    await screen.findByText('Almacén Norte')
    expect(screen.getByRole('button', { name: 'Registrar pago de Almacén Norte' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar pago de Distribuidora Sur' })).not.toBeInTheDocument()
  })

  it('marks a reseller and explains when the mark cannot change', async () => {
    let associated = 2
    const calls = mockApi(call => call.path === '/api/customers/regular-1/reseller-assignment'
      ? response({ kind: 'REGULAR', resellerId: null, associatedCustomersCount: 0, pendingOrders: [], pendingOrdersCount: 0 })
      : call.path === '/api/customers/reseller-1/reseller-assignment'
        ? response({ kind: 'RESELLER', resellerId: null, associatedCustomersCount: associated, pendingOrders: [], pendingOrdersCount: 0,
          commissionPlan: associated ? 'Revendedores' : null, pendingCommissionCount: associated ? 1 : 0 })
        : call.path === '/api/customers/client-1/reseller-assignment'
          ? response({ kind: 'REGULAR', resellerId: 'reseller-1', reseller: 'Distribuidora Sur', associatedCustomersCount: 0, pendingOrders: [], pendingOrdersCount: 0 })
          : call.method === 'PATCH' && call.path === '/api/customers/reseller-1/kind'
            ? response({ detail: 'El revendedor tiene clientes asociados. Reasignalos antes de quitar la marca.', code: 'RESELLER_CONFLICT' }, 409)
            : call.method === 'PATCH' ? response(undefined, 204) : undefined)
    const user = userEvent.setup()
    renderPage(['ADMIN_ALL', 'RESELLER_MANAGE'])
    await user.click(await screen.findByLabelText('Más acciones de Almacén Norte'))
    await user.click(await screen.findByRole('button', { name: 'Revendedor de Almacén Norte' }))
    let dialog = await screen.findByRole('dialog', { name: 'Revendedor de Almacén Norte' })
    await user.click(within(dialog).getByRole('radio', { name: 'Revendedor' }))
    expect(within(dialog).getByText(/Podrás asociarle clientes/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(calls).toContainEqual({ path: '/api/customers/regular-1/kind', method: 'PATCH', body: { kind: 'RESELLER' } }))
    expect(await screen.findByText('Almacén Norte ahora es revendedor.')).toBeInTheDocument()

    await user.click(await screen.findByLabelText('Más acciones de Kiosco Centro'))
    await user.click(screen.getByRole('button', { name: 'Revendedor de Kiosco Centro' }))
    dialog = await screen.findByRole('dialog', { name: 'Revendedor de Kiosco Centro' })
    expect(await within(dialog).findByRole('radio', { name: 'Revendedor' })).toBeDisabled()
    expect(within(dialog).getByText('Para marcarlo como revendedor, primero quitale su revendedor y guardá.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }))

    await user.click(await screen.findByLabelText('Más acciones de Distribuidora Sur'))
    await user.click(screen.getByRole('button', { name: 'Revendedor de Distribuidora Sur' }))
    dialog = await screen.findByRole('dialog', { name: 'Revendedor de Distribuidora Sur' })
    expect(await within(dialog).findByRole('radio', { name: 'Cliente' })).toBeDisabled()
    expect(within(dialog).getByText('Para quitar la marca, reasigná sus 2 clientes asociados, quitale el plan de comisión «Revendedores» y liquidá sus comisiones pendientes.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }))

    // The association changed meanwhile: the API conflict stays readable in the dialog.
    associated = 0
    const resellerActions = screen.getByRole('button', { name: 'Más acciones de Distribuidora Sur' })
    expect(resellerActions).toHaveFocus()
    await user.click(resellerActions)
    await user.click(screen.getByRole('button', { name: 'Revendedor de Distribuidora Sur' }))
    dialog = await screen.findByRole('dialog', { name: 'Revendedor de Distribuidora Sur' })
    await waitFor(() => expect(within(dialog).getByRole('radio', { name: 'Cliente' })).toBeEnabled())
    await user.click(within(dialog).getByRole('radio', { name: 'Cliente' }))
    expect(within(dialog).getByText(/Dejará de ser revendedor/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }))
    expect(await within(dialog).findByText('El revendedor tiene clientes asociados. Reasignalos antes de quitar la marca.')).toBeInTheDocument()
  })

  it('shows a reseller its associated customers and the debt of its account', async () => {
    sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/customers/reseller-1') return response({ ...reseller, number: '0003', associatedCustomersCount: 1 })
      if (path.startsWith('/api/customers/reseller-1/orders')) return response(page([]))
      if (path.includes('resellerId=reseller-1')) return response(page([{ ...client, number: '0004' }]))
      return response(page([]))
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/customers/reseller-1']}>
      <Routes><Route path="/customers/:customerId" element={<CustomerDetailPage />} /></Routes>
    </MemoryRouter></QueryClientProvider>)
    expect(await screen.findByText('Revendedor · 1 clientes asociados')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver deuda de la cuenta' })).toHaveAttribute('href', '/sales?accountCustomerId=reseller-1&pendingBalance=true')
    const associated = screen.getByRole('heading', { name: 'Clientes asociados' }).closest('section')!
    expect(await within(associated).findByText('Kiosco Centro')).toBeInTheDocument()
    expect(within(associated).getByRole('link', { name: 'Ver cliente Kiosco Centro' })).toHaveAttribute('href', '/customers/client-1')
  })

  it('assigns a reseller and lists the pending orders that keep their account', async () => {
    const calls = mockApi(call => call.path === '/api/customers/regular-1/reseller-assignment'
      ? response({ id: 'regular-1', name: 'Almacén Norte', kind: 'REGULAR', resellerId: null, reseller: null, associatedCustomersCount: 0, pendingOrdersCount: 1,
        pendingOrders: [{ id: 'order-1', number: '000300004', total: 1500, account: 'Almacén Norte' }] })
      : call.method === 'PATCH' ? response({ customerId: 'regular-1', resellerId: 'reseller-1', pendingOrdersKept: 1 }) : undefined)
    const user = userEvent.setup()
    renderPage(['ADMIN_ALL', 'RESELLER_MANAGE'])
    const regularRow = (await screen.findByText('Almacén Norte')).closest('tr')!
    await user.click(within(regularRow).getByLabelText('Más acciones de Almacén Norte'))
    await user.click(within(regularRow).getByRole('button', { name: 'Revendedor de Almacén Norte' }))
    const dialog = await screen.findByRole('dialog', { name: 'Revendedor de Almacén Norte' })
    expect(await within(dialog).findByText(/Solo cambian los pedidos nuevos/)).toBeInTheDocument()
    await selectEntity(user, 'Revendedor', 'Distribuidora Sur')
    expect(await within(dialog).findByText(/Hay 1 pedido sin entregar que seguirá en la cuenta actual/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Pedido 000300004/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(calls).toContainEqual({ path: '/api/customers/regular-1/reseller', method: 'PATCH', body: { resellerId: 'reseller-1' } }))
    expect(await screen.findByText('Revendedor asignado. Los pedidos nuevos del cliente se cargan a su cuenta.')).toBeInTheDocument()
  })
})
