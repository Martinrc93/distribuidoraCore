import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BusinessProfilePage from './BusinessProfilePage'
import CreditLimitPage from './CreditLimitPage'
import { SettingsEditorProvider } from './SettingsEditorState'
import { businessProfilePath, settingsPath } from './settingsPaths'

function response(body: unknown, status = 200) { return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response) }

describe('CreditLimitPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('keeps business details in draft when leaving the page and saves them for tickets', async () => {
    const user = userEvent.setup()
    const existing = { name: 'Distribuidora Central', address: 'Av. Ejemplo 123', phone: '011 4444-5555' }
    const saved = { ...existing, phone: '011 4444-7777' }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) =>
      String(input) === '/api/settings/business-profile'
        ? response(init?.method === 'PUT' ? saved : existing)
        : response({ creditLimit: null, enabled: false }))
    render(<QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[businessProfilePath]}>
        <SettingsEditorProvider><Routes>
          <Route path={businessProfilePath} element={<BusinessProfilePage />} />
          <Route path={settingsPath} element={<Link to={businessProfilePath}>Datos del comercio</Link>} />
        </Routes></SettingsEditorProvider>
      </MemoryRouter>
    </QueryClientProvider>)

    await user.click(screen.getByText('Datos de contacto', { exact: true }))
    expect(await screen.findByLabelText('Nombre del comercio')).toHaveValue(existing.name)
    expect(screen.getByLabelText('Dirección')).toHaveValue(existing.address)
    const phone = screen.getByLabelText('Teléfono')
    await user.clear(phone)
    await user.type(phone, saved.phone)
    await user.click(screen.getByRole('link', { name: 'Volver a Configuración' }))
    await user.click(screen.getByRole('link', { name: 'Datos del comercio' }))
    expect(screen.getByText('Sin guardar')).toBeVisible()
    await user.click(screen.getByText('Datos de contacto', { exact: true }))
    const draft = await screen.findByLabelText('Teléfono')
    expect(draft).toBeVisible()
    expect(draft).toHaveValue(saved.phone)
    await user.click(screen.getByRole('button', { name: 'Guardar datos del comercio' }))
    expect(await screen.findByText('Datos del comercio guardados.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/settings/business-profile', expect.objectContaining({
      method: 'PUT', body: JSON.stringify(saved),
    }))
  })

  it('loads and saves the global non-blocking credit limit', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/settings/credit-limit' && init?.method === 'PUT') return response({ creditLimit: 2500, enabled: true, updatedAt: '2026-09-24T10:00:00Z', updatedBy: 'user-1' })
      return response({ creditLimit: 1000, enabled: true, updatedAt: '2026-09-23T10:00:00Z', updatedBy: 'user-1' })
    })
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><SettingsEditorProvider><CreditLimitPage /></SettingsEditorProvider></MemoryRouter></QueryClientProvider>)

    const amount = await screen.findByRole('textbox', { name: 'Límite de crédito' })
    await user.clear(amount)
    await user.type(amount, '2500')
    await user.click(screen.getByRole('button', { name: /guardar límite/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/settings/credit-limit', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ creditLimit: 2500 }),
    })))
    expect(await screen.findByText('Límite de crédito actualizado.')).toBeInTheDocument()
  })

  it('parses Argentine comma and thousands formatted credit limits', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/settings/credit-limit' && init?.method === 'PUT') return response({ creditLimit: 12500.5, enabled: true, updatedAt: '2026-09-24T10:00:00Z', updatedBy: 'user-1' })
      return response({ creditLimit: 1000, enabled: true, updatedAt: '2026-09-23T10:00:00Z', updatedBy: 'user-1' })
    })
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><SettingsEditorProvider><CreditLimitPage /></SettingsEditorProvider></MemoryRouter></QueryClientProvider>)

    const amount = await screen.findByRole('textbox', { name: 'Límite de crédito' })
    await user.clear(amount)
    await user.type(amount, '12.500,50')
    await user.click(screen.getByRole('button', { name: /guardar límite/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/settings/credit-limit', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ creditLimit: 12500.5 }),
    })))
  })
})
