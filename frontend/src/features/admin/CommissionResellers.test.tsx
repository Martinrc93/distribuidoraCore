import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CommissionPlansPage from './CommissionPlansPage'
import CommissionSettlementsPage from './CommissionSettlementsPage'
import { selectEntity } from '../../test/selectEntity'

type Call = { path: string; method: string; body?: unknown }

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const page = (content: unknown[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 })
const resellers = [
  { id: 'reseller-1', name: 'Distribuidora Sur', status: 'ACTIVE', kind: 'RESELLER' },
  { id: 'reseller-2', name: 'Mayorista Oeste', status: 'INACTIVE', kind: 'RESELLER' },
]
const plans = [{ id: 'plan-1', name: 'Revendedores', ruleCount: 1, sellerCount: 0, resellerCount: 1 }]

function mockApi(overrides: (call: Call) => Promise<Response> | undefined = () => undefined) {
  const calls: Call[] = []
  vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
    const call = { path: String(input), method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined }
    calls.push(call)
    const custom = overrides(call)
    if (custom) return custom
    if (call.path === '/api/settings/commission-plans') return response(plans)
    if (call.path.startsWith('/api/customers?') && call.path.includes('kind=RESELLER')) return response(page(resellers))
    if (call.path.startsWith('/api/sellers?')) return response(page([]))
    return response(page([]))
  })
  return calls
}

function renderWith(element: ReactNode, path = '/') {
  sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[path]}>{element}</MemoryRouter></QueryClientProvider>)
}

describe('Reseller commissions', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('assigns a commission plan to a reseller with the shared customer picker', async () => {
    const calls = mockApi(call => call.path === '/api/settings/commission-plans/reseller-assignments/reseller-1'
      ? response(call.method === 'PUT' ? { customerId: 'reseller-1', planId: 'plan-1' } : { customerId: 'reseller-1', planId: null })
      : undefined)
    const user = userEvent.setup()
    renderWith(<CommissionPlansPage />)

    const row = (await screen.findByText('Revendedores', { selector: 'span' })).closest('tr')!
    expect(within(row).getAllByRole('cell').map(cell => cell.textContent)).toContain('1')
    expect(screen.getByRole('columnheader', { name: 'Revendedores asignados' })).toBeInTheDocument()
    expect(screen.getByText(/pedidos de sus clientes y compras propias/)).toBeInTheDocument()

    await selectEntity(user, 'Revendedor', 'Distribuidora Sur')
    const plan = await screen.findByRole('combobox', { name: 'Plan del revendedor' })
    await user.selectOptions(plan, 'plan-1')
    const panel = screen.getByRole('form', { name: 'Asignación a revendedores' })
    await user.click(within(panel).getByRole('button', { name: 'Guardar asignación' }))
    await waitFor(() => expect(calls).toContainEqual({ path: '/api/settings/commission-plans/reseller-assignments/reseller-1',
      method: 'PUT', body: { planId: 'plan-1', expectedPlanId: null } }))
    expect(await screen.findByText('Asignación guardada.')).toBeInTheDocument()
  })

  it('settles the commissions of a reseller and shows the customer of each order', async () => {
    const statement = {
      name: 'Distribuidora Sur',
      pending: { token: 'token-1', entryCount: 1, amount: '6.0000' },
      periodAmount: '6.0000',
      movements: { ...page([{ id: 'entry-1', orderId: 'order-1', orderNumber: '0007-00000001', customer: 'Kiosco Centro',
        product: 'Yerba 1 kg', returned: false, amount: '6.0000', occurredAt: '2026-11-10T15:00:00Z', paidAt: null }]), size: 20 },
    }
    const calls = mockApi(call => call.path.startsWith('/api/commissions/resellers/reseller-1/settlements')
      ? response({ id: 'settlement-1', amount: '6.0000', entryCount: 1, paidAt: '2026-11-19T12:00:00Z' }, 201)
      : call.path.startsWith('/api/commissions/resellers/reseller-1?') ? response(statement) : undefined)
    const user = userEvent.setup()
    renderWith(<CommissionSettlementsPage />, '/admin/settings/commissions/settlements')

    expect(screen.getByRole('combobox', { name: 'Comisiones de' })).toHaveValue('reseller')
    expect(screen.queryByRole('combobox', { name: 'Vendedor' })).not.toBeInTheDocument()
    await selectEntity(user, 'Revendedor', 'Distribuidora Sur')
    expect(await screen.findByRole('cell', { name: 'Kiosco Centro' })).toBeInTheDocument()
    expect(calls.some(call => call.path.startsWith('/api/sellers'))).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Registrar pago al revendedor' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/a Distribuidora Sur\. Movimientos incluidos: 1 movimiento\./)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Registrar pago' }))
    await waitFor(() => expect(calls).toContainEqual({ path: '/api/commissions/resellers/reseller-1/settlements', method: 'POST',
      body: { token: 'token-1' } }))
    expect(await screen.findByText(/registrado para Distribuidora Sur\./)).toBeInTheDocument()

    // Switching back to sellers clears the reseller and uses the seller picker.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Comisiones de' }), 'seller')
    expect(await screen.findByRole('combobox', { name: 'Vendedor' })).toBeInTheDocument()
    expect(screen.queryByText('Kiosco Centro')).not.toBeInTheDocument()
  })
})
