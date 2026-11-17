import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { CreateRoleDialog } from './CreateRoleDialog'
import { UserRoleDialog } from './UserRoleDialog'

const permissions = [{ code: 'ORDER_PREPARE', description: 'Preparar pedidos', adminOnly: false }, { code: 'ADMIN_ALL', description: 'Acceso completo', adminOnly: true }]
const roles = [{ code: 'ADMIN', description: 'Administrador', requiresSellerProfile: false }, { code: 'SELLER', description: 'Vendedor', requiresSellerProfile: true }, { code: 'LOGISTICS', description: 'Logística', requiresSellerProfile: true }]
const account = { id: 'u1', email: 'user@example.test', roles: 'SELLER', status: 'ACTIVE', sellerStatus: 'ACTIVE' }
const response = (body: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, json: async () => body } as Response)
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear() })

it.each([true, false])('creates only the selected permissions after explicit confirmation (permission=%s)', async selected => {
  const user = userEvent.setup(); const saved = vi.fn(async () => {}); const fetch = vi.spyOn(global, 'fetch').mockImplementation(() => response({}, 201))
  render(<CreateRoleDialog permissions={permissions} onClose={vi.fn()} onSaved={saved} />)
  expect(screen.getByLabelText('Nombre del rol')).toHaveFocus()
  expect(screen.getByRole('checkbox', { name: /Acceso completo/ })).toBeDisabled()
  await user.type(screen.getByLabelText('Nombre del rol'), 'Logística')
  await user.type(screen.getByLabelText('Código del rol'), 'logistics')
  if (selected) await user.click(screen.getByRole('checkbox', { name: /Preparar pedidos/ }))
  await user.click(screen.getByRole('button', { name: 'Revisar nuevo rol' }))
  expect(fetch).not.toHaveBeenCalled()
  const confirmation = screen.getByRole('dialog', { name: 'Crear rol Logística' })
  expect(confirmation).toHaveTextContent('Solo tendrá acceso a datos asignados')
  await user.click(within(confirmation).getByRole('button', { name: 'Crear rol' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(fetch).toHaveBeenCalledWith('/api/roles', expect.objectContaining({ method: 'POST', body: JSON.stringify({ code: 'LOGISTICS', description: 'Logística', permissionCodes: selected ? ['ORDER_PREPARE'] : [] }) }))
})

it('rejects reserved codes before confirmation and preserves the draft after a duplicate', async () => {
  const user = userEvent.setup(); const fetch = vi.spyOn(global, 'fetch').mockImplementation(() => response({ detail: 'Conflicto' }, 409))
  render(<CreateRoleDialog permissions={permissions} onClose={vi.fn()} onSaved={vi.fn()} />)
  await user.type(screen.getByLabelText('Nombre del rol'), 'Logística')
  await user.type(screen.getByLabelText('Código del rol'), 'ADMIN')
  await user.click(screen.getByRole('button', { name: 'Revisar nuevo rol' }))
  expect(screen.getByRole('alert')).toHaveTextContent('distinto de ADMIN y SELLER')
  expect(fetch).not.toHaveBeenCalled()
  await user.clear(screen.getByLabelText('Código del rol')); await user.type(screen.getByLabelText('Código del rol'), 'LOGISTICS')
  await user.click(screen.getByRole('button', { name: 'Revisar nuevo rol' }))
  await user.click(within(screen.getByRole('dialog', { name: 'Crear rol Logística' })).getByRole('button', { name: 'Crear rol' }))
  expect(await within(screen.getByRole('dialog', { name: 'Crear rol Logística' })).findByRole('alert')).toHaveTextContent('Ya existe un rol con ese código')
  await user.click(screen.getByRole('button', { name: 'Volver' }))
  expect(screen.getByLabelText('Código del rol')).toHaveValue('LOGISTICS')
})

it('asks before discarding a new role and retains the draft when continuing', async () => {
  const user = userEvent.setup(); const close = vi.fn()
  render(<CreateRoleDialog permissions={permissions} onClose={close} onSaved={vi.fn()} />)
  await user.type(screen.getByLabelText('Nombre del rol'), 'Logística')
  await user.click(screen.getByRole('button', { name: 'Cancelar' }))
  await user.click(screen.getByRole('button', { name: 'Seguir editando' }))
  expect(screen.getByLabelText('Nombre del rol')).toHaveValue('Logística'); expect(close).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Cancelar' })); await user.click(screen.getByRole('button', { name: 'Descartar cambios' }))
  expect(close).toHaveBeenCalledOnce()
})

it('replaces a user role only after confirming, preserving profile compatibility', async () => {
  const user = userEvent.setup(); const saved = vi.fn(async () => {}); const fetch = vi.spyOn(global, 'fetch').mockImplementation(() => response(undefined, 204))
  render(<UserRoleDialog user={account} roles={roles} onClose={vi.fn()} onSaved={saved} />)
  expect(screen.getByRole('option', { name: 'Administrador' })).toBeDisabled()
  await user.selectOptions(screen.getByLabelText('Nuevo rol'), 'LOGISTICS')
  await user.click(screen.getByRole('button', { name: 'Revisar cambio' }))
  expect(fetch).not.toHaveBeenCalled()
  const confirmation = screen.getAllByRole('dialog').at(-1)!
  expect(confirmation).toHaveTextContent('Se conservarán sus asignaciones e historial')
  await user.click(within(confirmation).getByRole('button', { name: 'Confirmar cambio de rol' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(fetch).toHaveBeenCalledWith('/api/users/u1/role', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ role: 'LOGISTICS' }) }))
})

it('disables assigned roles without an active profile and keeps role errors available for retry', async () => {
  const user = userEvent.setup(); const fetch = vi.spyOn(global, 'fetch').mockImplementation(() => response({ detail: 'El perfil cambió' }, 409))
  const { rerender } = render(<UserRoleDialog user={{ ...account, sellerStatus: 'INACTIVE' }} roles={roles} onClose={vi.fn()} onSaved={vi.fn()} />)
  expect(screen.getByRole('option', { name: 'Logística' })).toBeDisabled()
  rerender(<UserRoleDialog user={account} roles={roles} onClose={vi.fn()} onSaved={vi.fn()} />)
  await user.selectOptions(screen.getByLabelText('Nuevo rol'), 'LOGISTICS'); await user.click(screen.getByRole('button', { name: 'Revisar cambio' }))
  await user.click(screen.getByRole('button', { name: 'Confirmar cambio de rol' }))
  expect(await within(screen.getAllByRole('dialog').at(-1)!).findByRole('alert')).toHaveTextContent('El perfil cambió')
  expect(fetch).toHaveBeenCalledOnce()
  await user.click(screen.getByRole('button', { name: 'Volver' })); expect(screen.getByLabelText('Nuevo rol')).toHaveValue('LOGISTICS')
})
