import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import RolesPage from './RolesPage'

const permissions = [
  { code: 'ADMIN_ALL', description: 'Acceso administrativo completo', adminOnly: true },
  { code: 'ORDER_CREATE', description: 'Crear pedidos', adminOnly: false },
  { code: 'ORDER_PREPARE', description: 'Preparar pedidos', adminOnly: false },
  { code: 'ORDER_REVIEW', description: 'Revisar pedidos', adminOnly: true },
  { code: 'SALE_DELIVER', description: 'Entregar ventas', adminOnly: false },
]
const seller = { id: 'seller-role', code: 'SELLER', description: 'Vendedor', permissions: ['ORDER_CREATE'], requiredPermissions: [], assignedToCurrentUser: false }
const admin = { id: 'admin-role', code: 'ADMIN', description: 'Administrador', permissions: ['ADMIN_ALL', 'ORDER_REVIEW'], requiredPermissions: ['ADMIN_ALL'], assignedToCurrentUser: true }
const response = (body: unknown, status = 200) => Promise.resolve({ ok: status >= 200 && status < 300, status, json: async () => body } as Response)
function LoginResult() { const location = useLocation(); return <h1>{location.state?.rolePermissionsUpdated ? 'Ingresar con permisos actualizados' : 'Ingresar'}</h1> }
function setup(authorities = ['ADMIN_ALL']) {
  sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/admin/roles']}><Routes><Route path="/admin/roles" element={<RolesPage />} /><Route path="/orders" element={<h1>Pedidos</h1>} /><Route path="/login" element={<LoginResult />} /></Routes></MemoryRouter></QueryClientProvider>)
  return client
}
function mockCatalog(onPut?: (init?: RequestInit) => Promise<Response>) {
  return vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
    if (init?.method === 'PUT') return onPut?.(init) ?? response({ ...seller, permissions: ['ORDER_CREATE', 'ORDER_PREPARE'], currentSessionRevoked: false })
    return response(String(input) === '/api/roles' ? [admin, seller] : permissions)
  })
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear() })

it('loads roles and the full catalog and keeps administrator restrictions visible', async () => {
  mockCatalog()
  setup()
  await userEvent.click(await screen.findByRole('button', { name: 'Editar permisos de Vendedor' }))
  const dialog = screen.getByRole('dialog', { name: 'Permisos de Vendedor' })
  expect(within(dialog).getByRole('checkbox', { name: /Acceso administrativo completo/ })).toBeDisabled()
  expect(within(dialog).getByRole('checkbox', { name: /Revisar pedidos/ })).toBeDisabled()
  expect(within(dialog).getByRole('checkbox', { name: /Crear pedidos/ })).toBeChecked()
  expect(within(dialog).getByRole('checkbox', { name: /Preparar pedidos/ })).toBeEnabled()
  expect(within(dialog).getByRole('button', { name: 'Revisar cambios' })).toBeDisabled()
})

it('keeps the required administrative permission selected and explains complete access', async () => {
  mockCatalog(); setup()
  await userEvent.click(await screen.findByRole('button', { name: 'Editar permisos de Administrador' }))
  expect(screen.getByRole('checkbox', { name: /Acceso administrativo completo/ })).toBeDisabled()
  expect(screen.getByRole('checkbox', { name: /Acceso administrativo completo/ })).toBeChecked()
  expect(screen.getByText(/Quitar un permiso individual no limita ese acceso/)).toBeInTheDocument()
  expect(screen.getByText(/Este cambio cerrará tu sesión/)).toBeInTheDocument()
})

it('reviews additions and removals before sending a checked replacement', async () => {
  const user = userEvent.setup()
  const fetch = mockCatalog(() => response({ ...seller, permissions: ['ORDER_PREPARE'], currentSessionRevoked: false }))
  setup()
  await user.click(await screen.findByRole('button', { name: 'Editar permisos de Vendedor' }))
  await user.click(screen.getByRole('checkbox', { name: /Crear pedidos/ }))
  await user.click(screen.getByRole('checkbox', { name: /Preparar pedidos/ }))
  await user.click(screen.getByRole('button', { name: 'Revisar cambios' }))
  const confirm = screen.getByRole('dialog', { name: 'Guardar permisos de Vendedor' })
  expect(within(confirm).getByText(/Se agregan: Preparar pedidos. Se quitan: Crear pedidos/)).toBeInTheDocument()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(0)
  await user.click(within(confirm).getByRole('button', { name: 'Guardar permisos' }))
  expect(await screen.findByRole('status')).toHaveTextContent('Permisos de Vendedor guardados')
  expect(fetch).toHaveBeenCalledWith('/api/roles/SELLER/permissions', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ permissionCodes: ['ORDER_PREPARE'], expectedPermissionCodes: ['ORDER_CREATE'] }) }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('confirms discard, preserves the draft when dismissed and restores focus after discarding', async () => {
  const user = userEvent.setup(); mockCatalog(); setup()
  const trigger = await screen.findByRole('button', { name: 'Editar permisos de Vendedor' })
  await user.click(trigger)
  await user.click(screen.getByRole('checkbox', { name: /Preparar pedidos/ }))
  await user.click(screen.getByRole('button', { name: 'Cancelar' }))
  await user.click(screen.getByRole('button', { name: 'Seguir editando' }))
  expect(screen.getByRole('checkbox', { name: /Preparar pedidos/ })).toBeChecked()
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(trigger).toHaveFocus()
})

it('allows discarding every permission from a non-administrative role', async () => {
  const user = userEvent.setup()
  const fetch = mockCatalog(() => response({ ...seller, permissions: [], currentSessionRevoked: false }))
  setup()
  await user.click(await screen.findByRole('button', { name: 'Editar permisos de Vendedor' }))
  await user.click(screen.getByRole('checkbox', { name: /Crear pedidos/ }))
  await user.click(screen.getByRole('button', { name: 'Revisar cambios' }))
  await user.click(within(screen.getByRole('dialog', { name: 'Guardar permisos de Vendedor' })).getByRole('button', { name: 'Guardar permisos' }))
  await screen.findByRole('status')
  expect(fetch).toHaveBeenCalledWith('/api/roles/SELLER/permissions', expect.objectContaining({ body: JSON.stringify({ permissionCodes: [], expectedPermissionCodes: ['ORDER_CREATE'] }) }))
  expect(screen.getByText('Sin permisos asignados')).toBeInTheDocument()
})

it('keeps the draft and confirmation open after a save failure, then allows retry', async () => {
  const user = userEvent.setup(); let attempts = 0
  mockCatalog(() => ++attempts === 1 ? response({ detail: 'Servidor no disponible' }, 503) : response({ ...seller, permissions: ['ORDER_CREATE', 'ORDER_PREPARE'], currentSessionRevoked: false }))
  setup()
  await user.click(await screen.findByRole('button', { name: 'Editar permisos de Vendedor' }))
  await user.click(screen.getByRole('checkbox', { name: /Preparar pedidos/ }))
  await user.click(screen.getByRole('button', { name: 'Revisar cambios' }))
  const dialog = screen.getByRole('dialog', { name: 'Guardar permisos de Vendedor' })
  await user.click(within(dialog).getByRole('button', { name: 'Guardar permisos' }))
  expect(await within(dialog).findByRole('alert')).toHaveTextContent('Servidor no disponible')
  expect(screen.getByRole('checkbox', { name: /Preparar pedidos/ })).toBeChecked()
  await user.click(within(dialog).getByRole('button', { name: 'Guardar permisos' }))
  expect(await screen.findByRole('status')).toHaveTextContent('guardados')
})

it('stops stale overwrites and keeps the draft until an explicit discard', async () => {
  const user = userEvent.setup()
  const fetch = mockCatalog(() => response({ detail: 'Los permisos de este rol cambiaron.' }, 409))
  setup()
  await user.click(await screen.findByRole('button', { name: 'Editar permisos de Vendedor' }))
  await user.click(screen.getByRole('checkbox', { name: /Preparar pedidos/ }))
  await user.click(screen.getByRole('button', { name: 'Revisar cambios' }))
  await user.click(within(screen.getByRole('dialog', { name: 'Guardar permisos de Vendedor' })).getByRole('button', { name: 'Guardar permisos' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Los permisos de este rol cambiaron')
  expect(screen.getByRole('checkbox', { name: /Preparar pedidos/ })).toBeChecked()
  expect(screen.getByRole('button', { name: 'Revisar cambios' })).toBeDisabled()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1)
})

it('clears credentials and cached account data when the server revokes the current session', async () => {
  const user = userEvent.setup()
  mockCatalog(() => response({ ...admin, permissions: ['ADMIN_ALL'], currentSessionRevoked: true }))
  const client = setup(); client.setQueryData(['private-customer'], { name: 'Private customer' })
  sessionStorage.setItem('distribuidora.refreshToken', 'example-refresh-token')
  await user.click(await screen.findByRole('button', { name: 'Editar permisos de Administrador' }))
  await user.click(screen.getByRole('checkbox', { name: /Revisar pedidos/ }))
  await user.click(screen.getByRole('button', { name: 'Revisar cambios' }))
  await user.click(within(screen.getByRole('dialog', { name: 'Guardar permisos de Administrador' })).getByRole('button', { name: 'Guardar permisos' }))
  expect(await screen.findByRole('heading', { name: 'Ingresar con permisos actualizados' })).toBeInTheDocument()
  expect(sessionStorage.getItem('distribuidora.accessToken')).toBeNull()
  expect(sessionStorage.getItem('distribuidora.refreshToken')).toBeNull()
  expect(client.getQueryData(['private-customer'])).toBeUndefined()
})

it('does not offer partial editing when the permission catalog fails and retries both reads', async () => {
  const user = userEvent.setup(); let fail = true
  vi.spyOn(global, 'fetch').mockImplementation(input => String(input) === '/api/roles' ? response([admin, seller]) : fail ? response({ detail: 'Catálogo no disponible' }, 503) : response(permissions))
  setup()
  expect(await screen.findByRole('alert')).toHaveTextContent('Catálogo no disponible')
  expect(screen.queryByRole('button', { name: /Editar permisos de/ })).not.toBeInTheDocument()
  fail = false
  await user.click(screen.getByRole('button', { name: 'Reintentar' }))
  expect(await screen.findByRole('button', { name: 'Editar permisos de Vendedor' })).toBeInTheDocument()
})

it.each([['ORDER_CREATE'], ['USER_MANAGE']])('does not read roles without ADMIN_ALL: %s', authorities => {
  const fetch = mockCatalog(); setup([authorities])
  expect(screen.getByRole('heading', { name: 'Pedidos' })).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})
