import { useState, type FormEvent } from 'react'
import { apiPatch } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import type { Customer } from './customerTypes'

export function CustomerPriceListDialog({ customer, lists, loading, failed, retry, onClose, onSaved }: {
  customer: Customer; lists: Array<{ id: string; code: string; name: string; status: string }>; loading: boolean; failed: boolean
  retry: () => void; onClose: () => void; onSaved: () => Promise<void>
}) {
  const [value, setValue] = useState(customer.priceListId ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const changed = value !== (customer.priceListId ?? '')
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: changed, disabled: saving, onDiscard: onClose, protectUnload: true })
  async function save(event: FormEvent) {
    event.preventDefault()
    if (!changed || saving || loading || failed) return
    setSaving(true); setError('')
    try { await apiPatch(`/api/customers/${customer.id}/price-list`, { priceListId: value || null }); await onSaved() }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'No se pudo cambiar la lista.') }
    finally { setSaving(false) }
  }
  return <>
    <FormModal label={`Lista de precios de ${customer.name}`} size="compact" busy={saving} onClose={requestDiscard}>
      <Panel title="Lista de precios del cliente" description={customer.name}><form onSubmit={save}>
        <label className="field"><span>Lista de precios predeterminada</span><select className="select" value={value} disabled={saving || loading || failed} onChange={event => setValue(event.target.value)}>
          <option value="">Sin lista asignada (General)</option>
          {customer.priceListId && !lists.some(list => list.id === customer.priceListId) && <option value={customer.priceListId}>Lista asignada (sin cambios)</option>}
          {lists.filter(list => list.status === 'ACTIVE' || list.id === customer.priceListId).map(list => <option key={list.id} value={list.id} disabled={list.status !== 'ACTIVE'}>{list.code} - {list.name}{list.status !== 'ACTIVE' ? ' (inactiva)' : ''}</option>)}
        </select></label>
        {loading && <p role="status">Cargando listas...</p>}
        {failed && <p className="error-text" role="alert">No se pudieron cargar las listas. <Button type="button" variant="link" onClick={retry}>Reintentar listas</Button></p>}
        <p className="helper-text">Se aplicará a pedidos nuevos. Los precios históricos se conservan.</p>
        {error && <p className="error-text" role="alert">{error}</p>}
        <div className="page-actions mt-[20px] justify-end"><Button type="button" variant="secondary" disabled={saving} onClick={requestDiscard}>Cancelar</Button><Button disabled={!changed || saving || loading || failed}>{saving ? 'Guardando...' : 'Guardar lista'}</Button></div>
      </form></Panel>
    </FormModal>
    {discardDialog}
  </>
}
