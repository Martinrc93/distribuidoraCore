import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Navigate, useNavigate } from 'react-router-dom'
import { apiGet, apiPut, ApiError, clearAccessToken } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { FormModal } from '../../shared/components/FormModal'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { RolePermissionFields, type Permission } from './RolePermissionFields'
import { CreateRoleDialog } from './CreateRoleDialog'

type Role = { id: string; code: string; description: string; permissions: string[]; requiredPermissions: string[]; assignedToCurrentUser: boolean }
type UpdatedRole = Role & { currentSessionRevoked: boolean }
const queryKey = ['/api/roles', 'permissions']
const roleName = (role: Role) => role.description || role.code
const sameCodes = (left: string[], right: string[]) => left.length === right.length && left.every(code => right.includes(code))

function RolePermissionsEditor({ role, permissions, onClose, onSaved }: { role: Role; permissions: Permission[]; onClose: () => void; onSaved: (role: UpdatedRole) => Promise<void> }) {
  const [selected, setSelected] = useState<string[]>(role.permissions)
  const [confirming, setConfirming] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const hasChanges = !sameCodes(selected, role.permissions)
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges, onDiscard: onClose, disabled: saving, protectUnload: true })
  const added = selected.filter(code => !role.permissions.includes(code))
  const removed = role.permissions.filter(code => !selected.includes(code))
  const permissionName = (code: string) => permissions.find(permission => permission.code === code)?.description || code
  const sessionNotice = role.assignedToCurrentUser
    ? 'Este cambio cerrará tu sesión y la de los demás usuarios de este rol. Tendrás que volver a ingresar.'
    : 'Los usuarios de este rol tendrán que volver a ingresar para usar los nuevos permisos.'

  function review(event: FormEvent) {
    event.preventDefault()
    if (!hasChanges || saving || conflict) return
    setError('')
    setConfirming(true)
  }

  async function save() {
    if (saving || conflict) return
    setSaving(true)
    setError('')
    try {
      const result = await apiPut<UpdatedRole>(`/api/roles/${encodeURIComponent(role.code)}/permissions`, {
        permissionCodes: [...selected].sort(), expectedPermissionCodes: [...role.permissions].sort(),
      })
      await onSaved(result)
    } catch (cause) {
      setError(cause instanceof ApiError && cause.status === 403 ? 'No tenés permiso para modificar roles.'
        : cause instanceof Error ? cause.message : 'No se pudieron guardar los permisos. Volvé a intentar.')
      if (cause instanceof ApiError && cause.status === 409) { setConflict(true); setConfirming(false) }
    } finally { setSaving(false) }
  }

  return <>
    <FormModal label={`Permisos de ${roleName(role)}`} busy={saving} onClose={requestDiscard}>
      <Panel title={`Permisos de ${roleName(role)}`} description="Los cambios se aplican a todos los usuarios del rol.">
        <form onSubmit={review}>
          <p className="warning-text">{sessionNotice}</p>
          {role.permissions.includes('ADMIN_ALL') && <p className="helper-text">El acceso administrativo completo habilita todas las funciones administrativas. Quitar un permiso individual no limita ese acceso.</p>}
          <RolePermissionFields permissions={permissions} selected={selected} required={role.requiredPermissions} isAdminRole={role.code === 'ADMIN'} disabled={saving || conflict} onChange={codes => { setError(''); setSelected(codes) }} />
          <p className="helper-text">Los permisos conservan el alcance de cada usuario y no habilitan pantallas reservadas a administradores.</p>
          {hasChanges && <div className="role-permissions-changes" aria-live="polite"><p>Se agregan: {added.map(permissionName).join(', ') || 'ninguno'}.</p><p>Se quitan: {removed.map(permissionName).join(', ') || 'ninguno'}.</p></div>}
          {error && <p className="error-text" role="alert">{error}</p>}
          {conflict && <p className="helper-text">Cancelá esta edición y usá «Actualizar roles» para cargar los permisos actuales. Tu borrador se conserva hasta que decidas descartarlo.</p>}
          <div className="page-actions role-permissions-actions"><Button type="button" variant="secondary" disabled={saving} onClick={requestDiscard}>Cancelar</Button><Button type="submit" disabled={!hasChanges || saving || conflict}>Revisar cambios</Button></div>
        </form>
      </Panel>
    </FormModal>
    {discardDialog}
    {confirming && <ConfirmationDialog title={`Guardar permisos de ${roleName(role)}`} description={`Se agregan: ${added.map(permissionName).join(', ') || 'ninguno'}. Se quitan: ${removed.map(permissionName).join(', ') || 'ninguno'}. ${sessionNotice}`}
      confirmLabel="Guardar permisos" confirmVariant={removed.length ? 'danger' : 'primary'} pending={saving} pendingLabel="Guardando..." error={error} onCancel={() => setConfirming(false)} onConfirm={() => void save()} />}
  </>
}

export default function RolesPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const navigate = useNavigate()
  const client = useQueryClient()
  const [editing, setEditing] = useState<Role>()
  const [creating, setCreating] = useState(false)
  const [feedback, setFeedback] = useState('')
  const query = useQuery({ queryKey, enabled: isAdmin, retry: false, queryFn: async ({ signal }) => {
    const [roles, permissions] = await Promise.all([apiGet<Role[]>('/api/roles', signal), apiGet<Permission[]>('/api/permissions', signal)])
    return { roles, permissions }
  } })
  if (!isAdmin) return <Navigate to="/orders" replace />

  async function saved(role: UpdatedRole) {
    if (role.currentSessionRevoked) {
      await client.cancelQueries()
      client.clear()
      clearAccessToken()
      navigate('/login', { replace: true, state: { rolePermissionsUpdated: true } })
      return
    }
    client.setQueryData<typeof query.data>(queryKey, current => current ? { ...current, roles: current.roles.map(item => item.id === role.id ? role : item) } : current)
    setEditing(undefined)
    setFeedback(`Permisos de ${roleName(role)} guardados. Los usuarios de ese rol deberán volver a ingresar.`)
  }

  return <>
    <PageHeader eyebrow="Administración" title="Roles y permisos" description="Configurá qué acciones puede realizar cada rol." actions={<><Button type="button" disabled={!query.data || query.isError} onClick={() => { setFeedback(''); setCreating(true) }}>+ Crear rol</Button><Button type="button" variant="secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>{query.isFetching ? 'Actualizando...' : 'Actualizar roles'}</Button></>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    <Panel title="Roles existentes" description="Editar un rol cambia los permisos de todos sus usuarios.">
      {query.isPending ? <p role="status">Cargando roles y permisos...</p> : query.isError ? <div role="alert"><p className="error-text">No se pudieron cargar los roles y permisos. {query.error.message}</p><Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button></div>
        : query.data.roles.length === 0 ? <EmptyState title="No hay roles para mostrar" description="Todavía no hay roles disponibles." />
          : <DataTable className="roles-table" columns={[
            { key: 'name', label: 'Rol', emphasis: true, render: (value, row) => <div className="grid gap-[6px]"><span>{value}</span><span><Badge>{row.code}</Badge></span>{row.assigned === 'true' && <span className="helper-text m-0">Tu rol actual</span>}</div> },
            { key: 'permissions', label: 'Permisos asignados', render: (_value, row) => {
              const role = query.data.roles.find(item => item.id === row.id)!
              return role.permissions.length ? <ul className="role-permissions-list">{role.permissions.map(code => <li key={code}>{query.data.permissions.find(permission => permission.code === code)?.description || code}</li>)}</ul> : 'Sin permisos asignados'
            } },
            { key: 'actions', label: '', render: (_value, row) => <div className="table-row-actions"><Button type="button" variant="secondary" aria-label={`Editar permisos de ${row.name}`} onClick={() => { setFeedback(''); setEditing(query.data.roles.find(role => role.id === row.id)) }}>Editar permisos</Button></div> },
          ]} rows={query.data.roles.map(role => ({ id: role.id, code: role.code, name: roleName(role), assigned: String(role.assignedToCurrentUser) }))} />}
    </Panel>
    {creating && query.data && <CreateRoleDialog permissions={query.data.permissions} onClose={() => setCreating(false)} onSaved={async () => { await query.refetch(); setCreating(false); setFeedback('Rol personalizado creado. Podés asignarlo desde Usuarios.'); }} />}
    {editing && query.data && <RolePermissionsEditor role={editing} permissions={query.data.permissions} onClose={() => setEditing(undefined)} onSaved={saved} />}
  </>
}
