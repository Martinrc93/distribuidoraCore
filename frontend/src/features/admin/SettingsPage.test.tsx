import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SettingsEditorProvider } from './SettingsEditorState'
import SettingsPage from './SettingsPage'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

function renderSettings(creditLimit: { creditLimit: number | null; enabled: boolean } = { creditLimit: null, enabled: false }) {
  vi.spyOn(global, 'fetch').mockImplementation((input) => {
    const url = String(input)
    if (url === '/api/settings/credit-limit') return response(creditLimit)
    return response({})
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter><SettingsEditorProvider><SettingsPage /></SettingsEditorProvider></MemoryRouter></QueryClientProvider>)
}

describe('SettingsPage', () => {
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ sub: 'admin-1', authorities: ['ADMIN_ALL'] }))}.signature`))
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('renders all configuration sections, links and inactive credit limit status', async () => {
    renderSettings({ creditLimit: null, enabled: false })

    expect(screen.getByRole('heading', { name: /^Configuración$/ })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Secciones de Configuración' })
    expect(nav.querySelector('a[href="/admin/settings/business-profile"]')).toBeInTheDocument()
    expect(nav.querySelector('a[href="/admin/settings/credit-limit"]')).toBeInTheDocument()
    expect(nav.querySelector('a[href="/admin/settings/commissions"]')).toBeInTheDocument()
    expect(nav.querySelector('a[href="/admin/settings/shortcuts"]')).toBeInTheDocument()
    expect(await screen.findByText('Desactivado')).toBeInTheDocument()
  })

  it('displays Active badge when global credit limit is enabled', async () => {
    renderSettings({ creditLimit: 5000, enabled: true })
    expect(await screen.findByText('Activo')).toBeInTheDocument()
  })
})
