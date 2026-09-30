import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CustomersPage from './CustomersPage'

function Location() {
  return <output data-testid="location">{useLocation().search}</output>
}

function renderPage(entry = '/customers') {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={[entry]}><CustomersPage /><Location /></MemoryRouter>
  </QueryClientProvider>)
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status === 200, status, json: () => Promise.resolve(body) } as Response)
}

describe('customer list filters', () => {
  let requests: URL[]
  beforeEach(() => {
    requests = []
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname === '/api/customers/filter-options') return response({ sellers: [{ id: 'seller-1', name: 'Lucía' }, { id: 'seller-2', name: 'Ana' }] })
      if (url.pathname === '/api/customers') {
        requests.push(url)
        return response({ content: [{ id: 'customer-1', name: 'Almacén Norte', seller: 'Lucía', status: 'ACTIVE', balance: 100 }], page: Number(url.searchParams.get('page')), size: 20, totalElements: 21, totalPages: 2 })
      }
      if (url.pathname === '/api/zones') return response([])
      return response({ content: [] })
    })
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear() })

  it('defaults to active customers and combines all filters before resetting pagination', async () => {
    const user = userEvent.setup()
    renderPage('/customers?page=3')
    expect(await screen.findByText('Almacén Norte')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Estado' })).toHaveValue('ACTIVE')
    expect(requests.at(-1)?.searchParams.get('status')).toBe('ACTIVE')
    const seller = screen.getByRole('combobox', { name: 'Buscar por vendedor' })
    await waitFor(() => expect(seller).toBeEnabled())
    await user.click(seller)
    await user.clear(seller)
    await user.type(seller, 'LUCIA')
    await user.click(screen.getByRole('option', { name: 'Lucía' }))
    await user.selectOptions(screen.getByLabelText('Cuenta corriente'), 'true')
    await user.selectOptions(screen.getByLabelText('Estado', { exact: true }), 'INACTIVE')
    await user.type(screen.getByLabelText('Buscar clientes'), 'Norte')
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('search')).toBe('Norte'))
    expect(requests.at(-1)?.searchParams.get('sellerId')).toBe('seller-1')
    expect(requests.at(-1)?.searchParams.get('hasBalance')).toBe('true')
    expect(requests.at(-1)?.searchParams.get('status')).toBe('INACTIVE')
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0')
    const location = new URLSearchParams(screen.getByTestId('location').textContent ?? '')
    expect(location.get('sellerId')).toBe('seller-1')
    expect(location.get('hasBalance')).toBe('true')
    expect(location.get('status')).toBe('INACTIVE')
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('page')).toBe('1'))
    expect(requests.at(-1)?.searchParams.get('sellerId')).toBe('seller-1')
    await user.selectOptions(screen.getByLabelText('Estado', { exact: true }), 'ALL')
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('status')).toBe(''))
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0')
    expect(screen.getByTestId('location')).toHaveTextContent('status=ALL')
  })

  it('restores inactive and all status selections from the URL', async () => {
    renderPage('/customers?status=INACTIVE&sellerId=seller-1&hasBalance=true&search=Norte&page=1')
    await screen.findByText('Almacén Norte')
    expect(screen.getByLabelText('Estado', { exact: true })).toHaveValue('INACTIVE')
    expect(screen.getByLabelText('Cuenta corriente')).toHaveValue('true')
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Buscar por vendedor' })).toHaveValue('Lucía'))
    cleanup()
    renderPage('/customers?status=ALL')
    await screen.findByText('Almacén Norte')
    expect(screen.getByLabelText('Estado', { exact: true })).toHaveValue('ALL')
    expect(requests.at(-1)?.searchParams.get('status')).toBe('')
  })

  it('lets users retry seller options without blocking the customer list', async () => {
    const original = vi.mocked(fetch).getMockImplementation()!
    let failed = true
    vi.mocked(fetch).mockImplementation((input, init) => String(input) === '/api/customers/filter-options' && failed
      ? response({ detail: 'Unavailable' }, 500) : original(input, init))
    renderPage()
    await screen.findByText('Almacén Norte')
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudieron cargar los vendedores del filtro.')
    failed = false
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar filtro' }))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Buscar por vendedor' })).toBeEnabled())
  })

  it('explains empty filtered results without suggesting that the customer registry is empty', async () => {
    const original = vi.mocked(fetch).getMockImplementation()!
    vi.mocked(fetch).mockImplementation((input, init) => String(input).startsWith('/api/customers?')
      ? response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) : original(input, init))
    renderPage('/customers?hasBalance=true')
    expect(await screen.findByText('No hay clientes para mostrar')).toBeInTheDocument()
    expect(screen.getByText('No se encontraron clientes con los filtros seleccionados.')).toBeInTheDocument()
  })

  it('keeps seller-only sessions scoped while exposing balance and status filters', async () => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: [] }))}.signature`)
    renderPage()
    await screen.findByText('Almacén Norte')
    expect(screen.queryByRole('combobox', { name: 'Buscar por vendedor' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Cuenta corriente')).toBeInTheDocument()
    expect(screen.getByLabelText('Estado', { exact: true })).toHaveValue('ACTIVE')
  })

  it('refreshes seller options after creating a customer', async () => {
    const original = vi.mocked(fetch).getMockImplementation()!
    let optionLoads = 0
    vi.mocked(fetch).mockImplementation((input, init) => {
      if (String(input) === '/api/customers/filter-options') optionLoads++
      if (String(input) === '/api/customers' && init?.method === 'POST') return response({ id: 'new-customer' })
      return original(input, init)
    })
    const user = userEvent.setup()
    renderPage()
    await waitFor(() => expect(optionLoads).toBe(1))
    await user.click(screen.getByRole('button', { name: '+ Nuevo cliente' }))
    await user.type(screen.getByLabelText('Razón social'), 'New customer')
    await user.click(screen.getByRole('button', { name: 'Guardar cliente' }))
    await waitFor(() => expect(optionLoads).toBe(2))
    expect(await screen.findByText('Cliente creado correctamente.')).toBeInTheDocument()
  })
})
