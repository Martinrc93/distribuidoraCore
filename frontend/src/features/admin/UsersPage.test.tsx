import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ActivateUserPage, UsersPage } from './UsersPage'

function token(authorities: string[]) { return `header.${btoa(JSON.stringify({ authorities }))}.signature` }
function response(body?: unknown, status = 200) { return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response) }
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

  it('invites a user with role and provides an activation link', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input).startsWith('/api/users?')) return response(users)
      if (String(input) === '/api/users/invite' && init?.method === 'POST') return response({ userId: 'user-2', email: 'new@example.com', activationToken: 'activation-1', expiresAt: '2026-09-24T11:00:00Z' }, 201)
      return response({}, 204)
    })
    renderUsers()

    await user.click(await screen.findByRole('button', { name: /invitar usuario/i }))
    await user.type(screen.getByLabelText('Email del usuario'), 'new@example.com')
    await user.selectOptions(screen.getByLabelText('Rol'), 'SELLER')
    await user.type(screen.getByLabelText('Nombre para mostrar'), 'Nuevo vendedor')
    await user.click(screen.getByRole('button', { name: /enviar invitación/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users/invite', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ email: 'new@example.com', role: 'SELLER', displayName: 'Nuevo vendedor' }),
    })))
    expect(await screen.findByDisplayValue(/activate\?token=activation-1/)).toBeInTheDocument()
  })

  it('creates an account with a temporary password when requested', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input).startsWith('/api/users?')) return response(users)
      if (String(input) === '/api/users' && init?.method === 'POST') return response({ id: 'user-3' }, 201)
      return response({})
    })
    renderUsers()

    await user.click(await screen.findByRole('button', { name: /invitar usuario/i }))
    await user.click(screen.getByRole('button', { name: /crear con contraseña provisoria/i }))
    await user.type(screen.getByLabelText('Email del usuario'), 'temporary@example.com')
    await user.selectOptions(screen.getByLabelText('Rol'), 'ADMIN')
    await user.type(screen.getByLabelText('Contraseña provisoria'), 'Temporary123')
    await user.click(screen.getByRole('button', { name: /crear usuario/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ email: 'temporary@example.com', temporaryPassword: 'Temporary123', role: 'ADMIN', displayName: null }),
    })))
    expect(await screen.findByRole('status')).toHaveTextContent(/Usuario creado correctamente/)
  })

  it('confirms block/unblock and session revocation commands', async () => {
    const user = userEvent.setup()
    let status = 'ACTIVE'
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
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
})

describe('ActivateUserPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear() })

  it('submits the activation token and chosen password without requiring a session', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response(undefined, 204))
    render(<MemoryRouter initialEntries={['/activate?token=activation-1']}><Routes><Route path="/activate" element={<ActivateUserPage />} /></Routes></MemoryRouter>)

    await user.type(screen.getByLabelText('Nueva contraseña'), 'StrongPass123')
    await user.click(screen.getByRole('button', { name: /activar cuenta/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/auth/activate', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ activationToken: 'activation-1', password: 'StrongPass123' }),
    })))
    expect(await screen.findByText(/tu cuenta ya está activa/i)).toBeInTheDocument()
  })
})
