import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'

type User = { id: string; email: string; name: string; status: string; roles?: string | null }
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
  const isAdmin = hasAuthority('ADMIN_ALL') || hasAuthority('USER_MANAGE')
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const usersPath = `/api/users?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}`
  const usersKey = [usersPath]
  const query = useQuery({ queryKey: usersKey, queryFn: () => apiGet<ApiPage<User>>(usersPath) })
  const [showInvite, setShowInvite] = useState(false)
  const [creationMode, setCreationMode] = useState<'invite' | 'temporary'>('invite')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<'ADMIN' | 'SELLER'>('SELLER')
  const [displayName, setDisplayName] = useState('')
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [inviteLink, setInviteLink] = useState('')
  const [action, setAction] = useState<UserAction | undefined>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)

  async function saveUser(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) { setError('Ingresá un email válido.'); return }
    if (creationMode === 'temporary' && temporaryPassword.length < 8) { setError('La contraseña provisoria debe tener al menos 8 caracteres.'); return }
    setSaving(true)
    setError('')
    try {
      if (creationMode === 'invite') {
        const result = await apiPost<{ userId: string; email: string; activationToken: string; expiresAt: string }>('/api/users/invite', {
          email: email.trim(), role, displayName: displayName.trim() || null,
        })
        const link = new URL('/activate', window.location.origin)
        link.searchParams.set('token', result.activationToken)
        setInviteLink(link.toString())
        setFeedback(`Invitación creada para ${result.email}. Vence ${new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(result.expiresAt))}.`)
      } else {
        await apiPost('/api/users', { email: email.trim(), temporaryPassword, role, displayName: displayName.trim() || null })
        setFeedback('Usuario creado correctamente. Compartí la contraseña provisoria de manera segura.')
        setTemporaryPassword('')
      }
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/users?') })
      setEmail('')
      setDisplayName('')
      setShowInvite(false)
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
      </div>
    } }] : []),
  ]

  return <>
    <PageHeader eyebrow="Administración" title="Usuarios" description="Invitaciones, acceso y sesiones activas." actions={isAdmin ? <Button onClick={() => { setShowInvite((value) => !value); setInviteLink(''); setError(''); setCreationMode('invite') }}>+ Invitar usuario</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    {inviteLink && <Panel title="Enlace de activación"><label className="field"><span>Compartí este enlace con el usuario</span><input className="input" readOnly value={inviteLink} /></label></Panel>}
    {showInvite && isAdmin && <Panel title={creationMode === 'invite' ? 'Invitar usuario' : 'Crear usuario'}><form className="form-grid" onSubmit={saveUser}>
      <label className="field"><span>Email del usuario</span><input className="input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required disabled={saving} /></label>
      <label className="field"><span>Rol</span><select className="select" value={role} onChange={(event) => setRole(event.target.value as 'ADMIN' | 'SELLER')} disabled={saving}><option value="SELLER">Vendedor</option><option value="ADMIN">Administrador</option></select></label>
      <label className="field"><span>Nombre para mostrar</span><input className="input" value={displayName} onChange={(event) => setDisplayName(event.target.value)} maxLength={160} disabled={saving} /></label>
      {creationMode === 'temporary' && <label className="field"><span>Contraseña provisoria</span><input className="input" type="password" aria-label="Contraseña provisoria" autoComplete="new-password" minLength={8} value={temporaryPassword} onChange={(event) => setTemporaryPassword(event.target.value)} required disabled={saving} /></label>}
      <Button type="button" variant="link" onClick={() => { setCreationMode((mode) => mode === 'invite' ? 'temporary' : 'invite'); setError('') }} disabled={saving}>{creationMode === 'invite' ? 'Crear con contraseña provisoria' : 'Enviar invitación en su lugar'}</Button>
      <div className="page-actions"><Button type="button" variant="secondary" onClick={() => setShowInvite(false)} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : creationMode === 'invite' ? 'Enviar invitación' : 'Crear usuario'}</Button></div>
    </form></Panel>}
    <Panel>
      <div className="toolbar"><label className="field"><span>Buscar usuarios</span><input className="input search-input" aria-label="Buscar usuarios" placeholder="Email o nombre" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label></div>
      {query.isLoading ? <EmptyState title="Cargando usuarios" description="Consultando usuarios." /> : query.isError ? <EmptyState title="No se pudieron cargar los usuarios" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay usuarios para mostrar" description="Invitá a un usuario para habilitar el acceso." action={isAdmin ? <Button onClick={() => setShowInvite(true)}>+ Invitar usuario</Button> : undefined} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} usuarios</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {action && <div role="dialog" aria-modal="true" aria-labelledby="user-action-title" className="modal-backdrop"><Panel title="Confirmar acción"><h2 id="user-action-title">{action.action === 'block' ? `¿Bloquear ${action.user.email}?` : action.action === 'unblock' ? `¿Desbloquear ${action.user.email}?` : `¿Revocar todas las sesiones de ${action.user.email}?`}</h2><div className="page-actions"><Button variant="secondary" onClick={() => setAction(undefined)} disabled={saving}>Cancelar</Button><Button onClick={confirmAction} disabled={saving}>{saving ? 'Guardando...' : 'Confirmar'}</Button></div></Panel></div>}
  </>
}

export function ActivateUserPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const activationToken = searchParams.get('token') ?? ''
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [complete, setComplete] = useState(false)
  const [saving, setSaving] = useState(false)

  async function activate(event: FormEvent) {
    event.preventDefault()
    if (!activationToken) { setError('El enlace de activación no contiene un token válido.'); return }
    if (password.length < 8) { setError('La contraseña debe tener al menos 8 caracteres.'); return }
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
      <label className="field"><span>Nueva contraseña</span><input className="input" type="password" minLength={8} maxLength={200} autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} required disabled={saving || !activationToken} /></label>
      {error && <p className="error-text" role="alert">{error}</p>}
      <Button fullWidth disabled={saving || !activationToken}>{saving ? 'Activando...' : 'Activar cuenta'}</Button>
    </form>}
  </Panel></main>
}
