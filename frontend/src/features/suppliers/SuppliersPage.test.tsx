import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SuppliersPage from './SuppliersPage'

const supplier = { id: 'supplier-1', name: 'North supplier', phone: '123', email: 'north@example.test', address: 'Main 10' }
function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}
function renderPage(route = '/suppliers') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><SuppliersPage /></MemoryRouter></QueryClientProvider>)
}
function mockApi(failSave = false) {
  return vi.spyOn(global, 'fetch').mockImplementation((_input, init) => init?.method
    ? failSave ? response({ detail: 'No se pudo guardar el proveedor.' }, 500) : response({ id: 'supplier-2' }, init.method === 'POST' ? 201 : 204)
    : response({ content: [supplier], page: 0, size: 20, totalElements: 21, totalPages: 2 }))
}
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('SuppliersPage', () => {
  it('creates with only a name and refreshes the list', async () => {
    const fetchMock = mockApi()
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('North supplier')
    await user.click(screen.getByRole('button', { name: '+ Nuevo proveedor' }))
    expect(screen.getByLabelText('Nombre')).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Guardar proveedor' })).toBeDisabled()
    await user.type(screen.getByLabelText('Nombre'), ' New supplier ')
    await user.click(screen.getByRole('button', { name: 'Guardar proveedor' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Proveedor creado correctamente.')
    expect(fetchMock).toHaveBeenCalledWith('/api/suppliers', expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'New supplier', phone: null, email: null, address: null }) }))
    expect(fetchMock.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(2)
    expect(screen.getByRole('button', { name: '+ Nuevo proveedor' })).toHaveFocus()
  })

  it('edits all contacts and permits clearing optional fields', async () => {
    const fetchMock = mockApi()
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Editar proveedor North supplier' }))
    expect(screen.getByLabelText('Dirección (opcional)')).toHaveValue('Main 10')
    await user.clear(screen.getByLabelText('Teléfono (opcional)'))
    await user.clear(screen.getByLabelText('Mail (opcional)'))
    await user.clear(screen.getByLabelText('Dirección (opcional)'))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Proveedor actualizado correctamente.')
    expect(fetchMock).toHaveBeenCalledWith('/api/suppliers/supplier-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ name: 'North supplier', phone: null, email: null, address: null }) }))
  })

  it('retains entered data on error and asks before discarding edits', async () => {
    mockApi(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Editar proveedor North supplier' }))
    await user.type(screen.getByLabelText('Nombre'), ' changed')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo guardar el proveedor.')
    expect(screen.getByLabelText('Nombre')).toHaveValue('North supplier changed')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Seguir editando' }))
    expect(screen.getByLabelText('Nombre')).toHaveValue('North supplier changed')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })

  it('preserves search across pagination and resets page when searching', async () => {
    const fetchMock = mockApi()
    const user = userEvent.setup()
    renderPage('/suppliers?search=North')
    await user.click(await screen.findByRole('button', { name: 'Siguiente' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/suppliers?page=1&size=20&search=North', expect.anything()))
    await user.clear(screen.getByRole('searchbox'))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/suppliers?page=0&size=20&search=', expect.anything()))
  })

  it('shows a recoverable loading error', async () => {
    vi.spyOn(global, 'fetch').mockImplementationOnce(() => response({ detail: 'Failed to load' }, 500)).mockImplementation(() => response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }))
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('No hay proveedores en esta página')).toBeInTheDocument()
  })
})
