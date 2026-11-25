import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ActivateUserPage, UsersPage } from './UsersPage'

function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function response(body?: unknown, status = 200) { return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response) }
const roles = [{ code: 'ADMIN', description: 'Administrador', requiresSellerProfile: false }, { code: 'SELLER', description: 'Vendedor', requiresSellerProfile: true }, { code: 'LOGISTICS', description: 'Logística', requiresSellerProfile: true }]
function mockFetch(handler: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) { return vi.spyOn(global, 'fetch').mockImplementation((input, init) => String(input) === '/api/roles' ? response(roles) : handler(input, init)) }
const users = { content: [{ id: 'user-1', email: 'seller@example.com', name: 'seller@example.com', status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }

function renderUsers() {
  sessionStorage.setItem('distribuidora.accessToken', token(['ADMIN_ALL', 'USER_MANAGE']))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter><UsersPage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('UsersPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })
  beforeEach(() => vi.restoreAllMocks())

  it('shows role labels and allows active users to change roles', async () => {
    mockFetch((input) => String(input).startsWith('/api/users?')
      ? response({ ...users, content: [{ ...users.content[0], roles: 'SELLER,UNKNOWN_ROLE' }] })
      : response({}))
    renderUsers()

    expect(await screen.findByText('Vendedor')).toBeInTheDocument()
    expect(screen.getByText('UNKNOWN_ROLE')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /cambiar rol/i })).toBeInTheDocument()
  })

  it('creates a user with role and queues the invitation email', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch((input, init) => {
      if (String(input).startsWith('/api/users?')) return response(users)
      if (String(input) === '/api/users/invite' && init?.method === 'POST') return response({ userId: 'user-2', email: 'new@example.com', deliveryStatus: 'QUEUED', expiresAt: '2026-09-24T11:00:00Z' }, 201)
      return response({}, 204)
    })
    renderUsers()

    await user.click(await screen.findByRole('button', { name: /crear usuario/i }))
    await user.type(screen.getByLabelText('Email del usuario'), 'new@example.com')
    await user.selectOptions(screen.getByLabelText('Rol'), 'SELLER')
    await user.type(screen.getByLabelText('Nombre para mostrar'), 'Nuevo vendedor')
    await user.click(screen.getByRole('button', { name: /^confirmar$/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users/invite', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ email: 'new@example.com', role: 'SELLER', displayName: 'Nuevo vendedor' }),
    })))
    expect(await screen.findByRole('status')).toHaveTextContent('El enlace de invitación está pendiente de envío por email.')
    expect(screen.queryByText('Enlace de activación')).not.toBeInTheDocument()
  })

  it('keeps the form and email available when invitation email is not configured', async () => {
    const user = userEvent.setup()
    mockFetch((input) => String(input).startsWith('/api/users?')
      ? response(users)
      : response({ detail: 'El envío de invitaciones por email no está configurado.' }, 503))
    renderUsers()
    await user.click(await screen.findByRole('button', { name: /crear usuario/i }))
    await user.type(screen.getByLabelText('Email del usuario'), 'new@example.com')
    await user.type(screen.getByLabelText('Nombre para mostrar'), 'Nuevo vendedor')
    await user.click(screen.getByRole('button', { name: /^confirmar$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El envío de invitaciones por email no está configurado.')
    expect(screen.getByLabelText('Email del usuario')).toHaveValue('new@example.com')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('creates an account with a temporary password when requested', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch((input, init) => {
      if (String(input).startsWith('/api/users?')) return response(users)
      if (String(input) === '/api/users' && init?.method === 'POST') return response({ id: 'user-3' }, 201)
      return response({})
    })
    renderUsers()

    await user.click(await screen.findByRole('button', { name: /crear usuario/i }))
    await user.click(screen.getByRole('button', { name: /crear con contraseña provisoria/i }))
    await user.type(screen.getByLabelText('Email del usuario'), 'temporary@example.com')
    await user.selectOptions(screen.getByLabelText('Rol'), 'ADMIN')
    await user.type(screen.getByLabelText('Contraseña provisoria'), 'Temporary123')
    await user.click(screen.getByRole('button', { name: /^confirmar$/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ email: 'temporary@example.com', temporaryPassword: 'Temporary123', role: 'ADMIN', displayName: null }),
    })))
    expect(await screen.findByRole('status')).toHaveTextContent(/Usuario creado correctamente/)
  })

  it('confirms block/unblock and session revocation commands', async () => {
    const user = userEvent.setup()
    let status = 'ACTIVE'
    const fetchMock = mockFetch((input) => {
      if (String(input).startsWith('/api/users?')) return response({ ...users, content: [{ ...users.content[0], status }] })
      if (String(input) === '/api/users/user-1/block') { status = 'BLOCKED'; return response(undefined, 204) }
      if (String(input) === '/api/users/user-1/unblock') { status = 'ACTIVE'; return response(undefined, 204) }
      return response(undefined, 204)
    })
    renderUsers()

    await user.click(await screen.findByRole('button', { name: /bloquear usuario/i }))
    await user.click(screen.getByRole('button', { name: /confirmar/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users/user-1/block', expect.objectContaining({ method: 'POST' })))
    await user.click(await screen.findByRole('button', { name: /desbloquear usuario/i }))
    await user.click(screen.getByRole('button', { name: /confirmar/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users/user-1/unblock', expect.objectContaining({ method: 'POST' })))
    await user.click(screen.getByRole('button', { name: /revocar sesiones/i }))
    await user.click(screen.getByRole('button', { name: /confirmar/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users/user-1/revoke-sessions', expect.objectContaining({ method: 'POST' })))
  })

  it('loads custom roles for invitations and requires the assigned profile name', async () => {
    const user = userEvent.setup()
    const fetch = mockFetch((input) => String(input).startsWith('/api/users?') ? response(users) : response({ email: 'custom@example.test', expiresAt: '2026-11-17T12:00:00Z' }, 201))
    renderUsers()
    await user.click(await screen.findByRole('button', { name: /crear usuario/i }))
    await screen.findByRole('option', { name: 'Logística' })
    await user.selectOptions(screen.getByLabelText('Rol'), 'LOGISTICS')
    await user.type(screen.getByLabelText('Email del usuario'), 'custom@example.test')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    expect(screen.getByLabelText('Nombre para mostrar')).toBeInvalid()
    expect(fetch.mock.calls.some(([input]) => input === '/api/users/invite')).toBe(false)
    await user.type(screen.getByLabelText('Nombre para mostrar'), 'Asignado')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('status')
    expect(fetch).toHaveBeenCalledWith('/api/users/invite', expect.objectContaining({ body: JSON.stringify({ email: 'custom@example.test', role: 'LOGISTICS', displayName: 'Asignado' }) }))
  })

  it('blocks creation until a failed role catalog is retried successfully', async () => {
    const user = userEvent.setup(); let attempts = 0
    vi.spyOn(global, 'fetch').mockImplementation(input => String(input) === '/api/roles' ? ++attempts === 1 ? response({}, 503) : response(roles) : response(users))
    renderUsers()
    await user.click(await screen.findByRole('button', { name: /crear usuario/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudieron cargar los roles')
    expect(screen.getByRole('button', { name: 'Confirmar' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Reintentar roles' }))
    await screen.findByRole('option', { name: 'Logística' })
    expect(screen.getByRole('button', { name: 'Confirmar' })).toBeEnabled()
  })
})

describe('ActivateUserPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })

  it('submits the activation token and chosen password without requiring a session', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch(() => response(undefined, 204))
    render(<MemoryRouter initialEntries={['/activate?token=activation-1']}><Routes><Route path="/activate" element={<ActivateUserPage />} /></Routes></MemoryRouter>)

    await user.type(screen.getByLabelText('Contraseña', { exact: true }), 'StrongPass123')
    await user.type(screen.getByLabelText('Repetir contraseña'), 'StrongPass123')
    await user.click(screen.getByRole('button', { name: /activar cuenta/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/auth/activate', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ activationToken: 'activation-1', password: 'StrongPass123' }),
    })))
    expect(await screen.findByText(/tu cuenta ya está activa/i)).toBeInTheDocument()
  })

  it('rejects mismatched passwords and allows correcting the confirmation', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch(() => response(undefined, 204))
    render(<MemoryRouter initialEntries={['/activate?token=activation-1']}><ActivateUserPage /></MemoryRouter>)

    await user.type(screen.getByLabelText('Contraseña', { exact: true }), 'StrongPass123')
    const confirmation = screen.getByLabelText('Repetir contraseña')
    await user.type(confirmation, 'DifferentPass123')
    await user.click(screen.getByRole('button', { name: /activar cuenta/i }))

    expect(screen.getByRole('alert')).toHaveTextContent('Las contraseñas no coinciden.')
    expect(confirmation).toHaveAttribute('aria-invalid', 'true')
    expect(confirmation).toHaveAccessibleDescription('Las contraseñas no coinciden.')
    expect(fetchMock).not.toHaveBeenCalled()

    await user.clear(confirmation)
    await user.type(confirmation, 'StrongPass123')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /activar cuenta/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/tu cuenta ya está activa/i)).toBeInTheDocument()
  })

  it('requires a repeated password before submitting activation', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch(() => response(undefined, 204))
    render(<MemoryRouter initialEntries={['/activate?token=activation-1']}><ActivateUserPage /></MemoryRouter>)

    await user.type(screen.getByLabelText('Contraseña', { exact: true }), 'StrongPass123')
    await user.click(screen.getByRole('button', { name: /activar cuenta/i }))
    expect(screen.getByLabelText('Repetir contraseña')).toBeInvalid()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
