import { useState, type FormEvent } from 'react'
import { apiPut } from '../../shared/api/client'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { useDiscardChanges } from '../../shared/useDiscardChanges'

export type UserRoleOption = { code: string; description: string; requiresSellerProfile: boolean }
export type RoleUser = { id: string; email: string; status: string; roles?: string | null; sellerStatus?: string | null }

export function UserRoleDialog({ user, roles, onClose, onSaved }: { user: RoleUser; roles: UserRoleOption[]; onClose: () => void; onSaved: () => Promise<void> }) {
  const original = (user.roles ?? '').split(',').filter(Boolean)
  const [role, setRole] = useState(original.length === 1 ? original[0] : '')
  const [confirming, setConfirming] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const changed = Boolean(role && (original.length !== 1 || role !== original[0]))
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: changed, onDiscard: onClose, disabled: saving, protectUnload: true })
  const available = (value: UserRoleOption) => value.requiresSellerProfile ? user.sellerStatus === 'ACTIVE' : user.sellerStatus !== 'ACTIVE'
  function review(event: FormEvent) {
    event.preventDefault()
    if (!changed || saving) return
    const target = roles.find(item => item.code === role)
    if (!target || !available(target)) { setError('El rol no es compatible con el estado del perfil de vendedor.'); return }
    setError(''); setConfirming(true)
  }
  async function save() {
    if (saving) return
    setSaving(true); setError('')
    try { await apiPut(`/api/users/${user.id}/role`, { role }); await onSaved() }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'No se pudo cambiar el rol. Volvé a intentar.') }
    finally { setSaving(false) }
  }
  return <>
    <FormModal label={`Cambiar rol de ${user.email}`} size="compact" busy={saving} onClose={requestDiscard}><Panel title="Cambiar rol" description={user.email}>
      <form onSubmit={review}>
        <label className="field"><span>Nuevo rol</span><select className="select" required value={role} disabled={saving} onChange={event => setRole(event.target.value)}><option value="">Seleccionar rol</option>{roles.map(item => <option key={item.code} value={item.code} disabled={!available(item)}>{item.description || item.code}</option>)}</select></label>
        <p className="helper-text">Los roles con datos asignados requieren un perfil de vendedor activo y conservan sus clientes y pedidos. Para pasar a Administrador, primero desactivá ese perfil desde Vendedores.</p>
        {error && <p className="error-text" role="alert">{error}</p>}
        <div className="page-actions role-permissions-actions"><Button type="button" variant="secondary" disabled={saving} onClick={requestDiscard}>Cancelar</Button><Button type="submit" disabled={!changed || saving}>Revisar cambio</Button></div>
      </form>
    </Panel></FormModal>
    {discardDialog}
    {confirming && <ConfirmationDialog title={`Cambiar rol de ${user.email}`} description={`Se reemplazarán sus roles actuales por ${roles.find(item => item.code === role)?.description || role}. Sus sesiones se cerrarán; deberá volver a ingresar. Se conservarán sus asignaciones e historial.`} confirmLabel="Confirmar cambio de rol" confirmVariant="primary" pending={saving} error={error} onCancel={() => setConfirming(false)} onConfirm={() => void save()} />}
  </>
}
