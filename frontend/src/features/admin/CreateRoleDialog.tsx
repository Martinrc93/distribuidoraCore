import { useState, type FormEvent } from 'react'
import { apiPost, ApiError } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'
import { FormModal } from '../../shared/components/FormModal'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { RolePermissionFields, type Permission } from './RolePermissionFields'

export function CreateRoleDialog({ permissions, onClose, onSaved }: { permissions: Permission[]; onClose: () => void; onSaved: () => Promise<void> }) {
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [confirming, setConfirming] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: Boolean(code || description || selected.length), onDiscard: onClose, disabled: saving, protectUnload: true })
  function review(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!/^[A-Z][A-Z0-9_]{1,79}$/.test(code) || ['ADMIN', 'SELLER'].includes(code)) { setError('Usá un código de 2 a 80 letras mayúsculas, números o guiones bajos, distinto de ADMIN y SELLER.'); return }
    if (!description.trim()) { setError('Ingresá un nombre para el rol.'); return }
    setError(''); setConfirming(true)
  }
  async function save() {
    if (saving) return
    setSaving(true); setError('')
    try {
      await apiPost('/api/roles', { code, description: description.trim(), permissionCodes: [...selected].sort() })
      await onSaved()
    } catch (cause) { setError(cause instanceof ApiError && cause.status === 409 ? 'Ya existe un rol con ese código. Elegí otro.' : cause instanceof Error ? cause.message : 'No se pudo crear el rol. Volvé a intentar.') }
    finally { setSaving(false) }
  }
  return <>
    <FormModal label="Crear rol personalizado" busy={saving} onClose={requestDiscard}><Panel title="Crear rol personalizado" description="El rol tendrá acceso solo a datos asignados al usuario, mediante su perfil de vendedor.">
      <form onSubmit={review}>
        <div className="form-grid items-start">
          <label className="field"><span>Nombre del rol</span><input className="input" value={description} maxLength={255} required disabled={saving} onChange={event => setDescription(event.target.value)} /></label>
          <div className="field"><label htmlFor="new-role-code">Código del rol</label><input id="new-role-code" className="input" value={code} maxLength={80} required disabled={saving} aria-describedby="role-code-help" onChange={event => setCode(event.target.value.toUpperCase())} /><span id="role-code-help" className="helper-text m-0 font-normal">Identificador único: letras, números y guiones bajos. Por ejemplo, VENDEDOR_MAYORISTA.</span></div>
        </div>
        <RolePermissionFields permissions={permissions} selected={selected} disabled={saving} onChange={setSelected} />
        <p className="helper-text">No hereda los permisos del rol Vendedor. Podés crearlo sin permisos y configurarlos después.</p>
        {error && <p className="error-text" role="alert">{error}</p>}
        <div className="page-actions role-permissions-actions"><Button type="button" variant="secondary" disabled={saving} onClick={requestDiscard}>Cancelar</Button><Button type="submit" disabled={saving}>Revisar nuevo rol</Button></div>
      </form>
    </Panel></FormModal>
    {discardDialog}
    {confirming && <ConfirmationDialog title={`Crear rol ${description.trim()}`} description={`Permisos: ${selected.map(value => permissions.find(permission => permission.code === value)?.description || value).join(', ') || 'ninguno'}. Solo tendrá acceso a datos asignados; crear el rol no cambia los permisos de usuarios existentes.`} confirmLabel="Crear rol" confirmVariant="primary" pending={saving} error={error} onCancel={() => setConfirming(false)} onConfirm={() => void save()} />}
  </>
}
