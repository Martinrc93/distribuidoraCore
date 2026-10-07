import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SalesPage from './SalesPage'

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function Location() { return <output aria-label="URL">{useLocation().search}</output> }
const filterOptions = {
  customers: [{ id: 'customer-1', name: 'Almacén Norte' }, { id: 'customer-2', name: 'Mercado Sur' }],
  sellers: [{ id: 'seller-1', name: 'Ana' }, { id: 'seller-2', name: 'Lucía' }],
}
const sales = {
  content: [{ id: 'sale-1', number: 'VEN-001', customer: 'Almacén Norte', total: 300, paid: 100, balance: 200, status: 'DELIVERED', date: '2026-09-24T10:00:00Z' }],
  page: 0, size: 20, totalElements: 21, totalPages: 2,
}
const emptySales = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }
const defaultPath = '/api/sales?page=0&size=20&search=&customerId=&sellerId=&pendingBalance=false&dateMin=&dateMax='

function renderSales(initialEntry = '/sales') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[initialEntry]}><SalesPage /><Location /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('SalesPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', 'access-token'))

  it('shows sale totals, paid balance and status from the API', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => response(String(input) === '/api/sales/filter-options' ? filterOptions : sales))
    renderSales()
    expect(await screen.findByText('VEN-001')).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.getByText('Entregada')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(defaultPath, expect.anything())
    expect(screen.getByLabelText('Fecha mín.')).toHaveValue('')
    expect(screen.getByLabelText('Fecha máx.')).toHaveValue('')
  })

  it.each([
    ['ORDER_CREATE', 'Todas las entregadas', 'Consultá importes cobrados y saldos de tus ventas entregadas.'],
    ['ADMIN_ALL', 'Todas las ventas', 'Consultá importes cobrados, saldos y estado de las ventas.'],
  ])('describes the sales scope for %s', async (authority, option, description) => {
    sessionStorage.setItem('distribuidora.accessToken', token([authority]))
    vi.spyOn(global, 'fetch').mockImplementation(input => response(String(input) === '/api/sales/filter-options' ? filterOptions : sales))
    renderSales()
    await screen.findByText('VEN-001')
    expect(screen.getByText(description)).toBeInTheDocument()
    expect(screen.getByRole('option', { name: option })).toBeInTheDocument()
  })

  it('combines dropdown and date filters and preserves them across pages', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => response(String(input) === '/api/sales/filter-options' ? filterOptions : sales))
    renderSales('/sales?page=1&search=VEN&customerId=customer-1&sellerId=seller-2&dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026')
    expect(screen.getByLabelText('Buscar ventas')).toHaveValue('VEN')
    expect(screen.getByLabelText('Buscar ventas')).toHaveAttribute('placeholder', 'Número de venta')
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Buscar por cliente' })).toHaveValue('Almacén Norte'))
    expect(screen.getByRole('combobox', { name: 'Buscar por vendedor' })).toHaveValue('Lucía')
    expect(screen.getByLabelText('Fecha mín.')).toHaveValue('01/09/2026')
    expect(screen.getByLabelText('Fecha máx.')).toHaveValue('30/09/2026')
    await screen.findByText('VEN-001')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Saldo pendiente' }), 'true')
    const filteredPath = '/api/sales?page=0&size=20&search=VEN&customerId=customer-1&sellerId=seller-2&pendingBalance=true&dateMin=2026-09-01&dateMax=2026-09-30'
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(filteredPath, expect.anything()))
    expect(screen.getByLabelText('URL')).not.toHaveTextContent('page=')
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(filteredPath.replace('page=0', 'page=1'), expect.anything()))
    await user.click(screen.getByLabelText('Buscar por cliente'))
    await user.click(screen.getByRole('option', { name: 'Todos los clientes' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(filteredPath.replace('customerId=customer-1', 'customerId='), expect.anything()))
    await user.click(screen.getByLabelText('Buscar por vendedor'))
    await user.click(screen.getByRole('option', { name: 'Ana' }))
    await user.clear(screen.getByLabelText('Buscar ventas'))
    await user.type(screen.getByLabelText('Buscar ventas'), '001')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/sales?page=0&size=20&search=001&customerId=&sellerId=seller-1&pendingBalance=true&dateMin=2026-09-01&dateMax=2026-09-30', expect.anything()))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Saldo pendiente' }), '')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/sales?page=0&size=20&search=001&customerId=&sellerId=seller-1&pendingBalance=false&dateMin=2026-09-01&dateMax=2026-09-30', expect.anything()))
  })

  it('restores pending balance from the URL and shows empty results', async () => {
    vi.spyOn(global, 'fetch').mockImplementation((input) => response(String(input) === '/api/sales/filter-options' ? filterOptions : emptySales))
    renderSales('/sales?pendingBalance=true')
    expect(screen.getByRole('combobox', { name: 'Saldo pendiente' })).toHaveValue('true')
    expect(await screen.findByText('No se encontraron ventas con los filtros seleccionados.')).toBeInTheDocument()
  })

  it('searches names locally and applies the selected IDs only after choosing an option', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => response(String(input) === '/api/sales/filter-options' ? filterOptions : sales))
    renderSales('/sales?page=1')
    await screen.findByText('VEN-001')
    const customer = screen.getByRole('combobox', { name: 'Buscar por cliente' })
    const seller = screen.getByRole('combobox', { name: 'Buscar por vendedor' })
    await user.click(customer)
    await user.clear(customer)
    await user.type(customer, 'ALMACEN')
    expect(screen.getByRole('option', { name: 'Almacén Norte' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Mercado Sur' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('URL')).toHaveTextContent('page=1')
    expect(fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/sales?'))).toHaveLength(1)
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(defaultPath.replace('customerId=', 'customerId=customer-1'), expect.anything()))
    expect(customer).toHaveValue('Almacén Norte')
    expect(customer).toHaveFocus()
    await user.click(seller)
    await user.clear(seller)
    await user.type(seller, 'lucia')
    await user.click(screen.getByRole('option', { name: 'Lucía' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(defaultPath.replace('customerId=', 'customerId=customer-1').replace('sellerId=', 'sellerId=seller-2'), expect.anything()))
    await user.click(customer)
    await user.clear(customer)
    await user.type(customer, 'sin coincidencias')
    expect(screen.getByText('No se encontraron coincidencias.')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(customer).toHaveValue('Almacén Norte')
    expect(customer).toHaveAttribute('aria-expanded', 'false')
    await user.click(customer)
    await user.click(screen.getByRole('option', { name: 'Todos los clientes' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(defaultPath.replace('sellerId=', 'sellerId=seller-2'), expect.anything()))
  })

  it('validates dates before querying and permits clearing either boundary', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => response(String(input) === '/api/sales/filter-options' ? filterOptions : emptySales))
    renderSales()
    await screen.findByText('No hay ventas para mostrar')
    const min = screen.getByLabelText('Fecha mín.')
    const max = screen.getByLabelText('Fecha máx.')
    fireEvent.change(min, { target: { value: '31/09/2026' } })
    expect(screen.getByRole('alert')).toHaveTextContent('Las fechas deben ser válidas')
    expect(min).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/sales?'))).toHaveLength(1)
    fireEvent.change(min, { target: { value: '30/09/2026' } })
    fireEvent.change(max, { target: { value: '29/09/2026' } })
    expect(screen.getByRole('alert')).toHaveTextContent('La fecha mínima no puede ser posterior')
    fireEvent.change(min, { target: { value: '' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(defaultPath + '2026-09-29', expect.anything()))
    fireEvent.change(max, { target: { value: '' } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('allows retrying dropdown options without blocking the sales list', async () => {
    const user = userEvent.setup()
    let fail = true
    vi.spyOn(global, 'fetch').mockImplementation((input) => String(input) === '/api/sales/filter-options'
      ? fail ? response({ message: 'Unavailable' }, 503) : response(filterOptions)
      : response(emptySales))
    renderSales()
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudieron cargar los clientes y vendedores')
    expect(screen.getByLabelText('Buscar por cliente')).toBeDisabled()
    fail = false
    await user.click(screen.getByRole('button', { name: 'Reintentar filtros' }))
    await waitFor(() => expect(screen.getByLabelText('Buscar por cliente')).toBeEnabled())
    await user.click(screen.getByLabelText('Buscar por cliente'))
    expect(screen.getByRole('option', { name: 'Almacén Norte' })).toBeInTheDocument()
  })

  it('loads real sale line IDs and submits an authorized return', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL']))
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/sales/filter-options') return response(filterOptions)
      if (path.startsWith('/api/sales?page=')) return response(sales)
      if (path === '/api/sales/sale-1' && !init?.method) return response({
        sale: { id: 'sale-1', number: 'VEN-001', status: 'DELIVERED' },
        saleItems: [{ saleItemId: 'line-1', productId: 'product-1', productName: 'Harina', quantity: 2, returnedQuantity: 0, returnableQuantity: 2 }],
      })
      if (path === '/api/sales/sale-1/returns' && init?.method === 'POST') return response({ returnId: 'return-1' }, 201)
      return response({})
    })
    const queryClient = renderSales()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    await user.click(await screen.findByRole('button', { name: 'Ver venta' }))
    expect(await screen.findByText('Harina')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Motivo de la devolución'), 'Producto dañado')
    await user.type(screen.getByLabelText('Cantidad a devolver · Harina'), '1')
    await user.click(screen.getByRole('button', { name: 'Registrar devolución' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/sales/sale-1/returns', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ reason: 'Producto dañado', items: [{ saleItemId: 'line-1', quantity: 1 }] }),
    })))
    expect(await within(screen.getByRole('dialog')).findByRole('status')).toHaveTextContent(/stock actualizado/i)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sale-detail', 'sale-1'] })
  })
})
