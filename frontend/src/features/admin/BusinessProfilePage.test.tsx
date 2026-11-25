import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SettingsEditorProvider } from './SettingsEditorState'
import BusinessProfilePage from './BusinessProfilePage'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

function renderProfile() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter><SettingsEditorProvider><BusinessProfilePage /></SettingsEditorProvider></MemoryRouter></QueryClientProvider>)
}

describe('BusinessProfilePage', () => {
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ sub: 'admin-1', authorities: ['ADMIN_ALL'] }))}.signature`))
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('loads business contact info and saves updated phone', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/settings/business-profile' && init?.method === 'PUT') {
        return response(JSON.parse(String(init.body)))
      }
      if (path === '/api/settings/business-profile') return response({ name: 'Distribuidora Sur', address: 'Av. Principal 123', phone: '' })
      if (path === '/api/settings/business-logo') return response({ content: null })
      return response({})
    })

    renderProfile()
    await user.click(await screen.findByText('Datos de contacto'))
    const phone = screen.getByLabelText('Teléfono')
    await user.type(phone, '011 4444-5555')
    await user.click(screen.getByRole('button', { name: 'Guardar datos del comercio' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/settings/business-profile', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ name: 'Distribuidora Sur', address: 'Av. Principal 123', phone: '011 4444-5555' }),
    })))
    expect(await screen.findByText('Datos del comercio guardados.')).toHaveAttribute('role', 'status')
  })

  it('retries after failed profile load', async () => {
    vi.spyOn(global, 'fetch')
      .mockImplementationOnce(() => response({ detail: 'Error' }, 500))
      .mockImplementation((input) => String(input) === '/api/settings/business-logo'
        ? response({ content: null })
        : response({ name: 'Distribuidora Sur', address: '', phone: '' }))

    renderProfile()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Datos de contacto')).toBeInTheDocument()
  })
})
