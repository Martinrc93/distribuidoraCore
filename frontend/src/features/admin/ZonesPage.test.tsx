import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ZonesPage from './ZonesPage'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(status === 204 ? null : JSON.stringify(body), { status }))
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter><ZonesPage /></MemoryRouter></QueryClientProvider>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('ZonesPage', () => {
  it('lists, creates, edits and deletes delivery zones', async () => {
    const user = userEvent.setup()
    let zones = [{ id: 'zone-1', name: 'Norte', customerCount: 2 }]
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/zones' && (!init?.method || init.method === 'GET')) return response(zones)
      if (path === '/api/zones' && init?.method === 'POST') {
        zones = [...zones, { id: 'zone-2', name: 'Sur', customerCount: 0 }]
        return response({ id: 'zone-2', name: 'Sur', customerCount: 0 }, 201)
      }
      if (path === '/api/zones/zone-1' && init?.method === 'PUT') {
        zones = [{ id: 'zone-1', name: 'Centro', customerCount: 2 }]
        return response({}, 204)
      }
      if (path === '/api/zones/zone-1' && init?.method === 'DELETE') {
        zones = []
        return response({}, 204)
      }
      return response({})
    })

    renderPage()
    expect(await screen.findByText('Norte')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '+ Nueva zona' }))
    await user.type(screen.getByLabelText('Nombre de la zona'), 'Sur')
    await user.click(screen.getByRole('button', { name: 'Crear zona' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/zones', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'Sur' }),
    })))
    expect(await screen.findByText('Sur')).toBeInTheDocument()

    const northRow = screen.getByText('Norte').closest('tr')!
    await user.click(within(northRow).getByRole('button', { name: 'Editar' }))
    const input = screen.getByLabelText('Nombre de la zona')
    await user.clear(input)
    await user.type(input, 'Centro')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/zones/zone-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ name: 'Centro' }),
    })))
    expect(await screen.findByText('Centro')).toBeInTheDocument()

    const centroRow = screen.getByText('Centro').closest('tr')!
    await user.click(within(centroRow).getByRole('button', { name: 'Eliminar' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Eliminar zona' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/zones/zone-1', expect.objectContaining({ method: 'DELETE' })))
  })

  it('retries failed initial load', async () => {
    vi.spyOn(global, 'fetch')
      .mockImplementationOnce(() => response({ detail: 'Error' }, 500))
      .mockImplementation(() => response([]))
    renderPage()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Todavía no hay zonas')).toBeInTheDocument()
  })
})
