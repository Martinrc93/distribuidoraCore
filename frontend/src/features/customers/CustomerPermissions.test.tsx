import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../../app/App'
import CustomersPage from './CustomersPage'

const customer = { id: 'c1', name: 'Cliente asignado', cuitId: '30-123', email: 'customer@example.test', status: 'ACTIVE', balance: 100, sellerId: 'self', seller: 'Lucía', priceListId: 'general', createdAt: '2026-11-17T12:00:00Z' }
const page = (content: unknown[]) => ({ content, totalPages: 1, totalElements: content.length })
const response = (body: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, json: async () => body } as Response)
function mockFetch(override?: (path: string, init?: RequestInit) => Promise<Response> | undefined) {
  return vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
    const path = String(input); const result = override?.(path, init); if (result) return result
    if (path === '/api/customers/assignment-context') return response({ id: 'self', displayName: 'Lucía', email: 'self@example.test' })
    if (path === '/api/customers/c1/account-balance') return response(customer)
    if (path.includes('/orders?')) return response(page([{ id: 'o1', number: 'OWN-1', seller: 'Lucía', total: 100, balance: 100, status: 'CONFIRMED', date: '2026-11-17T12:00:00Z' }]))
    if (path === '/api/customers/c1' && !init?.method) return response(customer)
    if (path.startsWith('/api/customers?')) return response(page([customer]))
    if (path === '/api/zones') return response([])
    if (path.startsWith('/api/pricing/lists')) return response(page([{ id: 'general', code: 'GENERAL', name: 'General', status: 'ACTIVE' }, { id: 'other', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE' }]))
    return response({ id: 'created' }, init?.method === 'POST' ? 201 : 204)
  })
}
function mount(authorities: string[], route = '/customers', app = false) {
  sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={[route]}>{app ? <App /> : <CustomersPage />}</MemoryRouter></QueryClientProvider>)
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear() })

it('creates clients bound to the own seller without granting other actions', async () => {
  const user = userEvent.setup(); const fetch = mockFetch(); mount(['CUSTOMER_CREATE'])
  await user.click(await screen.findByRole('button', { name: '+ Nuevo cliente' }))
  const name = screen.getByLabelText('Razón social'); expect(name).toHaveFocus()
  await user.type(name, 'Nuevo cliente')
  expect(screen.getByRole('combobox', { name: 'Vendedor asignado' })).toBeDisabled()
  expect(screen.getByLabelText('Lista de precios predeterminada')).toBeDisabled()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Guardar cliente' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: 'Guardar cliente' }))
  await screen.findByRole('status')
  expect(fetch).toHaveBeenCalledWith('/api/customers', expect.objectContaining({ method: 'POST', body: JSON.stringify({ businessName: 'Nuevo cliente', cuitId: null, email: null, phone: null, address: null, zone: null, sellerId: 'self' }) }))
  expect(fetch.mock.calls.some(([path]) => String(path).startsWith('/api/sellers'))).toBe(false)
  expect(screen.queryByRole('button', { name: /^Editar/ })).not.toBeInTheDocument()
})

it('edits contact data while preserving a read-only seller and price list', async () => {
  const user = userEvent.setup(); const fetch = mockFetch(); mount(['CUSTOMER_EDIT'])
  await user.click(await screen.findByRole('button', { name: /^Editar/ }))
  expect(screen.getByRole('combobox', { name: 'Vendedor asignado' })).toBeDisabled()
  expect(screen.getByLabelText('Lista de precios predeterminada')).toBeDisabled()
  await user.clear(screen.getByLabelText('Mail')); await user.type(screen.getByLabelText('Mail'), 'updated@example.test')
  await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/customers/c1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ businessName: customer.name, cuitId: '30-123', email: 'updated@example.test', phone: null, address: null, zone: null, sellerId: 'self', priceListId: 'general' }) })))
  expect(screen.queryByRole('button', { name: /Desactivar cliente|Registrar pago/ })).not.toBeInTheDocument()
})

it('changes the default list independently from contact editing', async () => {
  const user = userEvent.setup(); const fetch = mockFetch(); mount(['CUSTOMER_PRICE_LIST_CHANGE'])
  await user.click(await screen.findByLabelText('Más acciones de ' + customer.name))
  await user.click(await screen.findByRole('button', { name: 'Cambiar lista' }))
  const modal = screen.getByRole('dialog', { name: `Lista de precios de ${customer.name}` })
  await user.selectOptions(within(modal).getByLabelText('Lista de precios predeterminada'), 'other')
  await user.click(within(modal).getByRole('button', { name: 'Guardar lista' }))
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/customers/c1/price-list', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ priceListId: 'other' }) })))
  expect(fetch.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
  expect(fetch.mock.calls.some(([path]) => path === '/api/zones' || path === '/api/customers/assignment-context')).toBe(false)
})

it('confirms status changes without granting editing or account collection', async () => {
  const user = userEvent.setup(); const fetch = mockFetch(); mount(['CUSTOMER_STATUS_CHANGE'])
  await user.click(await screen.findByLabelText('Más acciones de ' + customer.name))
  await user.click(await screen.findByRole('button', { name: 'Desactivar cliente' }))
  expect(fetch.mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(false)
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar' }))
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/customers/c1/status', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ status: 'INACTIVE' }) })))
})

it('collects an assigned balance without loading the privileged customer detail', async () => {
  const user = userEvent.setup(); const fetch = mockFetch((path) => path === '/api/customers/c1/account-balance' ? response({ ...customer, balance: 60 }) : undefined)
  mount(['ACCOUNT_SETTLE'])
  await user.click(await screen.findByRole('button', { name: `Registrar pago de ${customer.name}` }))
  const dialog = await screen.findByRole('dialog', { name: 'Pago de cuenta corriente' })
  expect(within(dialog).getByLabelText('Importe del pago')).toHaveValue('60,00')
  await user.click(within(dialog).getByRole('button', { name: 'Confirmar pago' }))
  expect(await screen.findByRole('status')).toHaveTextContent('Deuda asignada saldada correctamente.')
  expect(fetch).toHaveBeenCalledWith('/api/customers/c1/account-payments/settlement', expect.objectContaining({ method: 'POST', body: JSON.stringify({ payments: [{ method: 'CASH', amount: 60 }] }) }))
  expect(fetch.mock.calls.some(([path]) => path === '/api/customers/c1')).toBe(false)
})

it('opens an authorized customer route and hides unauthorized statement actions', async () => {
  mockFetch(); mount(['CUSTOMER_VIEW'], '/customers/c1', true)
  expect(await screen.findByRole('heading', { name: customer.name })).toBeInTheDocument()
  expect(screen.getByText('Saldo de tus ventas')).toBeInTheDocument()
  expect(await screen.findByText('OWN-1')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Descargar A4' })).not.toBeInTheDocument()
})

it('offers statement export independently from customer details', async () => {
  const user = userEvent.setup()
  mockFetch(); mount(['CUSTOMER_STATEMENT_EXPORT'])
  await user.click(await screen.findByLabelText('Más acciones de ' + customer.name))
  expect(await screen.findByRole('button', { name: 'Descargar estado de deuda' })).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /Ver cliente/ })).not.toBeInTheDocument()
})

it('preserves the draft while retrying a failed own assignment and confirms discard', async () => {
  const user = userEvent.setup(); let attempts = 0
  mockFetch(path => path === '/api/customers/assignment-context' && ++attempts === 1 ? response({}, 503) : undefined); mount(['CUSTOMER_CREATE'])
  await user.click(await screen.findByRole('button', { name: '+ Nuevo cliente' }))
  await user.type(screen.getByLabelText('Razón social'), 'Borrador')
  expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo cargar la asignación')
  expect(screen.getByRole('button', { name: 'Guardar cliente' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: 'Reintentar asignación' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Guardar cliente' })).toBeEnabled())
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  await user.click(screen.getByRole('button', { name: 'Seguir editando' }))
  expect(screen.getByLabelText('Razón social')).toHaveValue('Borrador')
  await user.click(screen.getByRole('button', { name: 'Cancelar' })); await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})
