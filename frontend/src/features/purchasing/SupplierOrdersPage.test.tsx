import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SupplierOrdersPage from './SupplierOrdersPage'

const paged = (content: unknown[], page = 0, totalPages = 1) => ({ content, page, size: 100, totalPages, totalElements: content.length * totalPages })
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }))
const supplierA = { id: 'supplier-a', name: 'Proveedor Norte' }
const supplierB = { id: 'supplier-b', name: 'Proveedor Sur' }
const product = { id: 'product-a', name: 'Harina', cost: 20, status: 'ACTIVE' }
const oldOrder = { id: 'old', number: 'PRV-00000001', supplierId: supplierA.id, supplier: supplierA.name, date: '2026-09-01', total: 30 }
const previous = { available: true, order: oldOrder, items: [{ productId: product.id, productName: product.name, quantity: 3, unitCost: 10, lineTotal: 30, currentCost: 25, status: 'ACTIVE' }] }
function fixture(input: RequestInfo | URL) {
  const path = String(input)
  if (path.startsWith('/api/suppliers')) return json(path.includes('page=1') ? paged([supplierA], 1, 2) : paged([supplierB], 0, 2))
  if (path.startsWith('/api/products')) return json(paged([product]))
  if (path.includes(`supplier/${supplierA.id}/last-order`)) return json(previous)
  if (path.includes('/last-order')) return json({ available: false })
  return json(paged([oldOrder]))
}
function renderPage(entry = '/supplier-orders') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}><SupplierOrdersPage /></MemoryRouter></QueryClientProvider>)
  return client
}
async function choose(label: string, name: string) {
  const user = userEvent.setup()
  await waitFor(() => expect(screen.getByRole('combobox', { name: label })).toBeEnabled())
  await user.click(screen.getByRole('combobox', { name: label }))
  await user.click(await screen.findByRole('option', { name }))
}
async function startForm() {
  await userEvent.setup().click(screen.getByRole('button', { name: '+ Nuevo pedido a proveedor' }))
  await choose('Proveedor', supplierA.name)
}
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('Supplier orders', () => {
  it('loads every supplier page and combines exact supplier and calendar filters, resetting pagination', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => fixture(input))
    renderPage('/supplier-orders?page=2')
    await screen.findByText(oldOrder.number)
    await choose('Buscar por proveedor', supplierA.name)
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '01/09/2026' } })
    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '30/09/2026' } })
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/supplier-orders?page=0&size=20&supplierId=supplier-a&dateMin=2026-09-01&dateMax=2026-09-30', expect.anything()))
    expect(fetch).toHaveBeenCalledWith('/api/suppliers?page=1&size=100', expect.anything())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Todas las fechas' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/supplier-orders?page=0&size=20&supplierId=supplier-a', expect.anything()))
  })
  it('blocks reversed or invalid date filters before requesting order history', () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => fixture(input))
    renderPage('/supplier-orders?dateMin=30%2F09%2F2026&dateMax=01%2F09%2F2026')
    expect(screen.getByRole('alert')).toHaveTextContent('La fecha inicial no puede ser posterior a la final.')
    expect(fetch.mock.calls.some(([input]) => String(input).startsWith('/api/supplier-orders'))).toBe(false)
  })
  it('copies the latest order of the selected supplier with current costs and preserves its quantities', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => init?.method === 'POST' ? json({ id: 'new', number: 'PRV-00000002', total: 75 }, 201) : fixture(input))
    renderPage()
    await startForm()
    const load = screen.getByRole('button', { name: 'Cargar pedido anterior' })
    await waitFor(() => expect(load).toBeEnabled())
    await userEvent.setup().click(load)
    expect(await screen.findByLabelText('Cantidad de Harina')).toHaveValue('3')
    expect(screen.getByLabelText('Costo de Harina')).toHaveValue('25')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Solicitar pedido' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Pedido PRV-00000002 registrado correctamente.')
    const body = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'POST')![1]!.body))
    expect(body).toMatchObject({ supplierId: supplierA.id, lines: [{ productId: product.id, quantity: 3, unitCost: 25 }] })
    expect(body.idempotencyKey).toBeTruthy()
  })
  it('allows a new order without history and adds products with editable quantity and cost', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => fixture(input))
    renderPage()
    await userEvent.setup().click(screen.getByRole('button', { name: '+ Nuevo pedido a proveedor' }))
    await choose('Proveedor', supplierB.name)
    await screen.findByText('Este proveedor no tiene pedidos anteriores.')
    expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toBeDisabled()
    await choose('Producto', product.name)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Agregar producto' }))
    expect(screen.getByLabelText('Cantidad de Harina')).toHaveValue('1')
    expect(screen.getByLabelText('Costo de Harina')).toHaveValue('20')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Quitar Harina' }))
    expect(screen.getByRole('button', { name: 'Solicitar pedido' })).toBeDisabled()
  })
  it('reuses the same idempotency payload after a failed submission and keeps the draft', async () => {
    const submissions: string[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      if (init?.method === 'POST') { submissions.push(String(init.body)); return submissions.length === 1 ? json({ detail: 'Error temporal' }, 500) : json({ id: 'new', number: 'PRV-00000002', total: 75 }, 201) }
      return fixture(input)
    })
    renderPage()
    await startForm()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cargar pedido anterior' })).toBeEnabled())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Cargar pedido anterior' }))
    await userEvent.setup().click(screen.getByRole('button', { name: 'Solicitar pedido' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Error temporal')
    expect(screen.getByLabelText('Cantidad de Harina')).toHaveValue('3')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Solicitar pedido' }))
    await screen.findByText(/Pedido PRV-00000002 registrado/)
    expect(submissions).toHaveLength(2)
    expect(submissions[0]).toBe(submissions[1])
  })
  it('confirms before replacing an existing draft with a previous order', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => fixture(input))
    renderPage()
    await startForm()
    await choose('Producto', product.name)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Agregar producto' }))
    await userEvent.setup().click(screen.getByRole('button', { name: 'Cargar pedido anterior' }))
    expect(await screen.findByRole('dialog')).toHaveTextContent('¿Reemplazar los productos?')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Volver' }))
    expect(screen.getByLabelText('Cantidad de Harina')).toHaveValue('1')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Cargar pedido anterior' }))
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reemplazar productos' }))
    expect(screen.getByLabelText('Cantidad de Harina')).toHaveValue('3')
  })
})
