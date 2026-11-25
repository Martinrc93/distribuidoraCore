import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ShrinkagesPage from './ShrinkagesPage'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

function renderPage(entry = '/products/shrinkages') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}><ShrinkagesPage /></MemoryRouter></QueryClientProvider>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('ShrinkagesPage', () => {
  it('renders total loss, filters by dates, validates inverted range and resets dates', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({
      totalQuantity: 4.5,
      movements: {
        content: [{ id: 's-1', product: 'Harina', date: '2026-10-12T15:30:00Z', quantity: 4.5, reason: 'Bolsa rota' }],
        page: 0, size: 20, totalElements: 1, totalPages: 1,
      },
    }))
    renderPage('/products/shrinkages?stockSearch=%3Fsearch%3Dharina')

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.getByText('Bolsa rota')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Volver a Stock' })).toHaveAttribute('href', '/products?search=harina')

    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '01/10/2026' } })
    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '15/10/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/inventory/shrinkages?page=0&size=20&dateMin=2026-10-01&dateMax=2026-10-15', expect.any(Object)))

    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '20/10/2026' } })
    expect(screen.getByRole('alert')).toHaveTextContent('La fecha inicial no puede ser posterior')

    await userEvent.setup().click(screen.getByRole('button', { name: 'Todas las fechas' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/inventory/shrinkages?page=0&size=20', expect.any(Object)))
  })

  it('offers retry when loading fails', async () => {
    vi.spyOn(global, 'fetch')
      .mockImplementationOnce(() => response({ detail: 'Error de red' }, 500))
      .mockImplementation(() => response({ totalQuantity: 0, movements: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }))
    renderPage()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('No hay mermas en el período seleccionado.')).toBeInTheDocument()
  })
})
