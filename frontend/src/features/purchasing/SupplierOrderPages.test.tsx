import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SupplierOrderCreatePage from './SupplierOrderCreatePage'
import SupplierOrderRestockPage from './SupplierOrderRestockPage'

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }))
const paged = (content: unknown[]) => ({ content, page: 0, size: 100, totalPages: 1, totalElements: content.length })

function renderRoute(entry: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}>
    <Routes>
      <Route path="/supplier-orders/new" element={<SupplierOrderCreatePage />} />
      <Route path="/supplier-orders/restock" element={<SupplierOrderRestockPage />} />
      <Route path="/products" element={<p>Stockdestino</p>} />
    </Routes>
  </MemoryRouter></QueryClientProvider>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('SupplierOrderCreatePage & SupplierOrderRestockPage', () => {
  it('renders SupplierOrderCreatePage with heading and loads form', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => json(paged([])))
    renderRoute('/supplier-orders/new')
    expect(screen.getByRole('heading', { name: 'Nueva compra' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Proveedor' })).toBeEnabled())
  })

  it('preloads restock product, suggests rounded quantity, submits and links back to filtered stock', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url = String(input)
      if (url.startsWith('/api/suppliers')) return json(paged([{ id: 'sup-1', name: 'Proveedor Norte' }]))
      if (url.startsWith('/api/products')) return json(paged([{
        id: 'prod-1', name: 'Harina', cost: 25.5, stock: -2.5, status: 'ACTIVE', suppliers: [{ id: 'sup-1', name: 'Proveedor Norte' }],
      }]))
      if (url.includes('/last-order')) return json({ available: false })
      if (url === '/api/supplier-orders' && init?.method === 'POST') return json({ id: 'ord-9', number: 'PRV-00000009', total: 89.25, status: 'PENDING' }, 201)
      return json({})
    })

    renderRoute('/supplier-orders/restock?productId=prod-1&source=stock&filters=stock%3DNEGATIVE')

    expect(screen.getByRole('heading', { name: 'Reposición' })).toBeInTheDocument()
    expect(await screen.findByLabelText('Cantidad de Harina')).toHaveValue('3,5')
    await user.click(screen.getByRole('button', { name: 'Guardar compra' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/supplier-orders', expect.objectContaining({ method: 'POST' })))
    expect(await screen.findByText(/Compra guardada como pendiente/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver compra' })).toHaveAttribute('href', '/supplier-orders/ord-9')
    await user.click(screen.getByRole('button', { name: 'Volver a Stock' }))
    expect(await screen.findByText('Stockdestino')).toBeInTheDocument()
  })

  it('blocks inactive product on restock route', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input)
      if (url.startsWith('/api/products')) return json(paged([{ id: 'prod-1', name: 'Harina', stock: 0, status: 'INACTIVE' }]))
      return json(paged([]))
    })

    renderRoute('/supplier-orders/restock?productId=prod-1&source=stock&filters=stock%3DNEGATIVE')
    expect(await screen.findByRole('alert')).toHaveTextContent('El producto ya no está disponible para reposición.')
    expect(screen.queryByRole('button', { name: 'Guardar compra' })).not.toBeInTheDocument()
  })
})
