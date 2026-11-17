import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { apiGet, apiPost, ApiError, clearAccessToken, type ApiPage } from '../../shared/api/client'
import { hasAuthority, getCurrentUserId } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { FormModal } from '../../shared/components/FormModal'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { UserRoleDialog, type UserRoleOption } from './UserRoleDialog'

type User = { id: string; email: string; name: string; status: string; roles?: string | null; sellerStatus?: string | null }
type UserAction = { user: User; action: 'block' | 'unblock' | 'revoke-sessions' }
type Row = Record<string, string>
function statusName(value: string) {
  return value === 'ACTIVE' ? 'Activo' : value === 'BLOCKED' ? 'Bloqueado' : value === 'INVITED' ? 'Invitado' : value
}

function roleName(value: string) {
  return value === 'ADMIN' ? 'Administrador' : value === 'SELLER' ? 'Vendedor' : value
}

function errorMessage(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 403) return 'No tenés permiso para administrar usuarios.'
  return cause instanceof Error ? cause.message : 'No se pudo completar la operación.'
}

export function UsersPage() {
  const navigate = useNavigate()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const usersPath = `/api/users?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}`
  const usersKey = [usersPath]
  const query = useQuery({ queryKey: usersKey, queryFn: () => apiGet<ApiPage<User>>(usersPath) })
  const [showInvite, setShowInvite] = useState(false)
  const [creationMode, setCreationMode] = useState<'invite' | 'temporary'>('invite')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('SELLER')
  const [roleUser, setRoleUser] = useState<User>()
  const [displayName, setDisplayName] = useState('')
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [action, setAction] = useState<UserAction | undefined>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)

  const rolesQuery = useQuery({ queryKey: ['/api/roles', 'user-options'], queryFn: ({ signal }) => apiGet<UserRoleOption[]>('/api/roles', signal), enabled: isAdmin && (showInvite || Boolean(roleUser)), retry: false })
  const selectedRole = rolesQuery.data?.find(item => item.code === role)
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: showInvite && Boolean(email || displayName || temporaryPassword || role !== 'SELLER'), disabled: saving, protectUnload: true, onDiscard: () => { setShowInvite(false); setEmail(''); setDisplayName(''); setTemporaryPassword(''); setRole('SELLER'); setError('') } })
  async function roleSaved() {
    if (roleUser?.id === getCurrentUserId()) {
      await queryClient.cancelQueries(); queryClient.clear(); clearAccessToken()
      navigate('/login', { replace: true, state: { userRoleUpdated: true } })
      return
    }
    await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/users') || queryKey[0] === '/api/roles' })
    setRoleUser(undefined); setFeedback('Rol actualizado. El usuario deberá volver a ingresar.')
  }

  async function saveUser(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!selectedRole || rolesQuery.isError) { setError('Cargá el catálogo de roles antes de guardar.'); return }
    if (selectedRole.requiresSellerProfile && !displayName.trim()) { setError('Ingresá el nombre para el perfil de vendedor.'); return }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) { setError('Ingresá un email válido.'); return }
    if (creationMode === 'temporary' && temporaryPassword.length < 8) { setError('La contraseña provisoria debe tener al menos 8 caracteres.'); return }
    setSaving(true)
    setError('')
    try {
      if (creationMode === 'invite') {
        const result = await apiPost<{ userId: string; email: string; deliveryStatus: string; expiresAt: string }>('/api/users/invite', {
          email: email.trim(), role, displayName: displayName.trim() || null,
        })
        setFeedback(`Cuenta creada para ${result.email}. El enlace de invitación está pendiente de envío por email. Vence ${new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(result.expiresAt))}.`)
      } else {
        await apiPost('/api/users', { email: email.trim(), temporaryPassword, role, displayName: displayName.trim() || null })
        setFeedback('Usuario creado correctamente. Compartí la contraseña provisoria de manera segura.')
        setTemporaryPassword('')
      }
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/users?') })
      setEmail('')
      setDisplayName('')
      setShowInvite(false)
      setRole('SELLER')
      setCreationMode('invite')
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setSaving(false)
    }
  }

  async function confirmAction() {
    if (!action) return
    setSaving(true)
    setError('')
    try {
      await apiPost(`/api/users/${action.user.id}/${action.action}`, {})
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/users?') })
      setFeedback(action.action === 'block' ? 'Usuario bloqueado.' : action.action === 'unblock' ? 'Usuario desbloqueado.' : 'Sesiones revocadas.')
      setAction(undefined)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setSaving(false)
    }
  }

  const filtered = query.data?.content ?? []
  const rows: Row[] = filtered.map((user) => ({ id: user.id, email: user.email, name: user.name, status: user.status, roles: user.roles ?? '' }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Usuario', emphasis: true },
    { key: 'email', label: 'Email' },
    { key: 'roles', label: 'Roles', render: (value) => {
      const roles = value.split(',').map((role) => role.trim()).filter(Boolean)
      return roles.length ? <div className="page-actions" aria-label={`Roles: ${roles.map(roleName).join(', ')}`}>{roles.map((role) => <Badge key={role} tone="soft">{roleName(role)}</Badge>)}</div> : <span>—</span>
    } },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{statusName(value)}</Badge> },
    ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Row) => {
      const item = filtered.find((user) => user.id === row.id)
      if (!item) return null
      return <div className="page-actions">
        <Button variant="link" onClick={() => setAction({ user: item, action: item.status === 'BLOCKED' ? 'unblock' : 'block' })}>{item.status === 'BLOCKED' ? 'Desbloquear usuario' : 'Bloquear usuario'}</Button>
        <Button variant="link" onClick={() => setAction({ user: item, action: 'revoke-sessions' })}>Revocar sesiones</Button>
        {item.status === 'ACTIVE' && <Button type="button" variant="link" aria-label={`Cambiar rol de ${item.email}`} onClick={() => { setError(''); setRoleUser(item) }}>Cambiar rol</Button>}
      </div>
    } }] : []),
  ]

  return <>
    <PageHeader eyebrow="Administración" title="Usuarios" description="Invitaciones, acceso y sesiones activas." actions={isAdmin ? <Button onClick={() => { setShowInvite((value) => !value); setError(''); setCreationMode('invite') }}>+ Crear usuario</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && !showInvite && <p className="error-text" role="alert">{error}</p>}
    {roleUser && (rolesQuery.isPending || rolesQuery.isError) && <FormModal label={`Cambiar rol de ${roleUser.email}`} size="compact" onClose={() => setRoleUser(undefined)}><Panel title="Cambiar rol" description={roleUser.email}>
      {rolesQuery.isPending ? <p role="status">Cargando roles...</p> : <p className="error-text" role="alert">No se pudieron cargar los roles.</p>}
      <div className="page-actions"><Button type="button" variant="secondary" onClick={() => setRoleUser(undefined)}>Cancelar</Button>{rolesQuery.isError && <Button type="button" onClick={() => void rolesQuery.refetch()}>Reintentar roles</Button>}</div>
    </Panel></FormModal>}
    {showInvite && isAdmin && <FormModal label="Crear usuario" busy={saving} onClose={requestDiscard}><Panel title="Crear usuario"><form className="form-grid" onSubmit={saveUser}>
      <label className="field"><span>Email del usuario</span><input className="input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required disabled={saving} /></label>
      <label className="field"><span>Rol</span><select className="select" value={role} onChange={event => setRole(event.target.value)} disabled={saving || rolesQuery.isPending || rolesQuery.isError}>{rolesQuery.isPending && <option value="SELLER">Cargando roles...</option>}{rolesQuery.data?.map(item => <option key={item.code} value={item.code}>{item.description || item.code}</option>)}</select></label>
      <label className="field"><span>Nombre para mostrar</span><input className="input" value={displayName} onChange={(event) => setDisplayName(event.target.value)} maxLength={160} required={selectedRole?.requiresSellerProfile} disabled={saving} /></label>
      {selectedRole?.requiresSellerProfile && <p className="helper-text">Se creará su perfil de vendedor para vincular únicamente sus datos asignados. No se agregarán permisos de otros roles.</p>}
      {creationMode === 'temporary' && <label className="field"><span>Contraseña provisoria</span><input className="input" type="password" aria-label="Contraseña provisoria" autoComplete="new-password" minLength={8} value={temporaryPassword} onChange={(event) => setTemporaryPassword(event.target.value)} required disabled={saving} /></label>}
      <Button type="button" variant="link" onClick={() => { setCreationMode((mode) => mode === 'invite' ? 'temporary' : 'invite'); setError('') }} disabled={saving}>{creationMode === 'invite' ? 'Crear con contraseña provisoria' : 'Enviar invitación en su lugar'}</Button>
      {rolesQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los roles. <Button type="button" variant="secondary" onClick={() => void rolesQuery.refetch()}>Reintentar roles</Button></p>}
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button type="button" variant="secondary" onClick={requestDiscard} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving || rolesQuery.isPending || rolesQuery.isError || !selectedRole}>{saving ? 'Guardando...' : 'Confirmar'}</Button></div>
    </form></Panel></FormModal>}
    {discardDialog}
    {roleUser && rolesQuery.data && !rolesQuery.isError && <UserRoleDialog user={roleUser} roles={rolesQuery.data} onClose={() => setRoleUser(undefined)} onSaved={roleSaved} />}
    <Panel>
      <div className="toolbar"><label className="field"><span>Buscar usuarios</span><input className="input search-input" aria-label="Buscar usuarios" placeholder="Email o nombre" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label></div>
      {query.isLoading ? <EmptyState title="Cargando usuarios" description="Consultando usuarios." /> : query.isError ? <EmptyState title="No se pudieron cargar los usuarios" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay usuarios para mostrar" description="Invitá a un usuario para habilitar el acceso." action={isAdmin ? <Button onClick={() => setShowInvite(true)}>+ Crear usuario</Button> : undefined} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} usuarios</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {action && <div role="dialog" aria-modal="true" aria-labelledby="user-action-title" className="modal-backdrop"><Panel title="Confirmar acción"><h2 id="user-action-title">{action.action === 'block' ? `¿Bloquear ${action.user.email}?` : action.action === 'unblock' ? `¿Desbloquear ${action.user.email}?` : `¿Revocar todas las sesiones de ${action.user.email}?`}</h2><div className="page-actions"><Button variant="secondary" onClick={() => setAction(undefined)} disabled={saving}>Cancelar</Button><Button onClick={confirmAction} disabled={saving}>{saving ? 'Guardando...' : 'Confirmar'}</Button></div></Panel></div>}
  </>
}

export function ActivateUserPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const activationToken = searchParams.get('token') ?? ''
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [error, setError] = useState('')
  const [complete, setComplete] = useState(false)
  const [saving, setSaving] = useState(false)
  const passwordMismatch = error === 'Las contraseñas no coinciden.'

  async function activate(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!activationToken) { setError('El enlace de activación no contiene un token válido.'); return }
    if (password.length < 8) { setError('La contraseña debe tener al menos 8 caracteres.'); return }
    if (password !== passwordConfirmation) { setError('Las contraseñas no coinciden.'); return }
    setSaving(true)
    setError('')
    try {
      await apiPost('/api/auth/activate', { activationToken, password })
      setComplete(true)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setSaving(false)
    }
  }

  return <main className="login-page"><Panel title="Activar cuenta" description={complete ? 'Tu cuenta quedó activada.' : 'Elegí una contraseña para habilitar tu acceso.'}>
    {complete ? <><p role="status">Tu cuenta ya está activa. Iniciá sesión para continuar.</p><Button fullWidth onClick={() => navigate('/login', { replace: true })}>Ir a iniciar sesión</Button></> : <form className="login-form" onSubmit={activate}>
      {!activationToken && <p className="error-text" role="alert">El enlace no es válido o no incluye el token de activación.</p>}
      <label className="field"><span>Contraseña</span><input className="input" type="password" minLength={8} maxLength={200} autoComplete="new-password" value={password} onChange={(event) => { setPassword(event.target.value); setError('') }} required disabled={saving || !activationToken} /></label>
      <label className="field"><span>Repetir contraseña</span><input className="input" type="password" minLength={8} maxLength={200} autoComplete="new-password" value={passwordConfirmation} onChange={(event) => { setPasswordConfirmation(event.target.value); setError('') }} aria-invalid={passwordMismatch || undefined} aria-describedby={passwordMismatch ? 'activation-error' : undefined} required disabled={saving || !activationToken} /></label>
      {error && <p id="activation-error" className="error-text" role="alert">{error}</p>}
      <Button fullWidth disabled={saving || !activationToken}>{saving ? 'Activando...' : 'Activar cuenta'}</Button>
    </form>}
  </Panel></main>
}
