import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CustomerDetailPage from './CustomerDetailPage'
import CustomersPage from './CustomersPage'

const customer = { id: 'customer-1', number: '0001', name: 'Almacén Norte', cuitId: '30-123', email: 'cliente@example.test', phone: '123456', address: 'San Martín 100', zone: 'Centro', seller: 'Lucía', priceList: 'Mayorista', priceListCode: 'MAYORISTA', status: 'INACTIVE', balance: -120.50, createdAt: '2025-01-01T12:00:00Z' }
const order = { id: 'order-1', number: 'PED-001', seller: 'Lucía', total: 500.50, status: 'CANCELLED', date: '2025-01-01T02:00:00Z' }
const paged = (content: unknown[], page = 0, totalElements = content.length) => ({ content, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) })
const response = (body: unknown, status = 200) => Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)

function renderPage(url = '/customers/customer-1', authorities = ['ADMIN_ALL']) {
  sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={[url]}><Routes>
      <Route path="/customers/:customerId" element={<CustomerDetailPage />} />
      <Route path="/customers" element={<CustomersPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider>)
}

afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

describe('customer details and order history', () => {
  it('offers an exact customer detail link for administrators and preserves list filters', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).startsWith('/api/customers?') ? response(paged([customer])) : response(paged([])))
    renderPage('/customers?status=INACTIVE&page=2', ['ADMIN_ALL'])
    const link = await screen.findByRole('link', { name: 'Ver cliente Almacén Norte' })
    expect(link).toHaveAttribute('href', '/customers/customer-1?returnTo=%2Fcustomers%3Fstatus%3DINACTIVE%26page%3D2')
  })

  it('shows full details, credit balance and cancelled orders with Argentina dates', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).includes('/orders?') ? response(paged([order])) : response(customer))
    renderPage('/customers/customer-1?returnTo=%2Fcustomers%3Fstatus%3DINACTIVE')
    expect(await screen.findByRole('heading', { name: customer.name })).toBeInTheDocument()
    expect(screen.getByText('0001', { exact: true })).toBeInTheDocument()
    for (const value of ['30-123', 'cliente@example.test', '123456', 'San Martín 100', 'Centro', 'MAYORISTA - Mayorista', 'Inactivo']) expect(screen.getByText(value)).toBeInTheDocument()
    expect(screen.getByText('Saldo a favor del cliente.')).toBeInTheDocument()
    const row = await screen.findByRole('row', { name: /PED-001/ })
    expect(row).toHaveTextContent('Cancelado')
    expect(row).toHaveTextContent('31/12/2024')
    expect(within(row).getByRole('link', { name: 'Ver pedido PED-001' })).toHaveAttribute('href', '/orders/order-1')
    expect(screen.getByRole('link', { name: 'Volver a clientes' })).toHaveAttribute('href', '/customers?status=INACTIVE')
  })

  it('loads the next history page for the same customer and keeps counts in the URL', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (!path.includes('/orders?')) return response(customer)
      const second = path.includes('page=1')
      return response(paged([{ ...order, number: second ? 'PED-OLD' : 'PED-NEW' }], second ? 1 : 0, 21))
    })
    renderPage()
    await screen.findByRole('row', { name: /PED-NEW/ })
    await userEvent.setup().click(screen.getByRole('button', { name: 'Siguiente' }))
    await screen.findByRole('row', { name: /PED-OLD/ })
    expect(screen.queryByRole('row', { name: /PED-NEW/ })).not.toBeInTheDocument()
    expect(screen.getByText('Página 2 · 21 pedidos')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/customers/customer-1/orders?page=1&size=20', expect.any(Object))
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled()
  })

  it('shows optional values and an empty history without pagination', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).includes('/orders?') ? response(paged([])) : response({ id: 'customer-1', name: 'Cliente sin datos', status: 'ACTIVE', balance: 0 }))
    renderPage()
    await screen.findByRole('heading', { name: 'Cliente sin datos' })
    expect(screen.getByText('Sin lista asignada')).toBeInTheDocument()
    expect(screen.getByText('Sin asignar')).toBeInTheDocument()
    await screen.findByRole('heading', { name: 'No hay pedidos para mostrar' })
    expect(screen.queryByRole('button', { name: 'Siguiente' })).not.toBeInTheDocument()
    expect(screen.getByText('El cliente no tiene saldo pendiente.')).toBeInTheDocument()
  })

  it('keeps customer details visible when history fails and retries only the history', async () => {
    let fail = true
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).includes('/orders?') ? fail ? response({ detail: 'Server error' }, 500) : response(paged([order])) : response(customer))
    renderPage()
    await screen.findByRole('heading', { name: 'No se pudo cargar el historial' })
    expect(screen.getByText(customer.email)).toBeInTheDocument()
    fail = false
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar historial' }))
    await screen.findByRole('row', { name: /PED-001/ })
    expect(fetchMock.mock.calls.filter(([path]) => String(path) === '/api/customers/customer-1')).toHaveLength(1)
  })

  it('hides history when customer access fails and rejects an external return link', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({ detail: 'Missing' }, 404))
    renderPage('/customers/customer-1?returnTo=https%3A%2F%2Fexample.test')
    await screen.findByRole('heading', { name: 'Cliente no disponible' })
    expect(screen.getByRole('link', { name: 'Volver a clientes' })).toHaveAttribute('href', '/customers')
    expect(fetchMock.mock.calls.some(([path]) => String(path).includes('/orders?'))).toBe(false)
  })

  it('retries failed customer data before requesting history', async () => {
    let fail = true
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input).includes('/orders?') ? response(paged([])) : fail ? response({}, 500) : response(customer))
    renderPage()
    await screen.findByRole('heading', { name: 'No se pudo cargar el cliente' })
    fail = false
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar datos' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: customer.name })).toBeInTheDocument())
    await screen.findByRole('heading', { name: 'No hay pedidos para mostrar' })
  })
})
