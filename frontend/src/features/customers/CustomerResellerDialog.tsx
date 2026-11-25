import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useState, type FormEvent } from 'react'
import { apiGet, apiPatch, ApiError } from '../../shared/api/client'
import { queryKeys } from '../../shared/api/queryKeys'
import { formatMoney } from '../../shared/format'
import { apiGetAllPages } from '../../shared/api/pagination'
import { Button } from '../../shared/components/Button'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { CustomerSelect } from '../../shared/components/EntitySelect'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import type { Customer } from './customerTypes'

type PendingOrder = { id: string; number: string; total: number; account: string }
type Assignment = {
  kind?: string; resellerId?: string | null; reseller?: string | null; associatedCustomersCount?: number
  commissionPlan?: string | null; pendingCommissionCount?: number
  pendingOrders: PendingOrder[]; pendingOrdersCount: number
}
type Kind = 'REGULAR' | 'RESELLER'

const money = (value: unknown) => formatMoney(value, 2)

/**
 * Administrative reseller settings of a customer: whether it is a reseller, or which reseller owes its new orders.
 * Only one change is saved at a time, because each one has its own conditions.
 */
export function CustomerResellerDialog({ customer, onClose, onSaved }: {
  customer: Customer; onClose: () => void; onSaved: (message: string) => Promise<void>
}) {
  const id = useId()
  const assignmentPath = `/api/customers/${customer.id}/reseller-assignment`
  const resellersPath = '/api/customers?page=0&size=100&kind=RESELLER&status=ACTIVE'
  const assignmentQuery = useQuery({ queryKey: queryKeys.customers.resellerAssignment(customer.id), queryFn: ({ signal }) => apiGet<Assignment>(assignmentPath, signal) })
  const resellersQuery = useQuery({ queryKey: queryKeys.customers.resellers('active'), queryFn: ({ signal }) => apiGetAllPages<Customer>(resellersPath, signal) })
  const currentKind: Kind = (assignmentQuery.data?.kind ?? customer.kind) === 'RESELLER' ? 'RESELLER' : 'REGULAR'
  const currentReseller = assignmentQuery.data?.resellerId ?? ''
  const [kind, setKind] = useState<Kind>(customer.kind === 'RESELLER' ? 'RESELLER' : 'REGULAR')
  const [resellerId, setResellerId] = useState(customer.resellerId ?? '')
  useEffect(() => {
    if (!assignmentQuery.data) return
    setKind(assignmentQuery.data.kind === 'RESELLER' ? 'RESELLER' : 'REGULAR')
    setResellerId(assignmentQuery.data.resellerId ?? '')
  }, [assignmentQuery.data])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const loading = assignmentQuery.isPending || resellersQuery.isPending
  const failed = assignmentQuery.isError || resellersQuery.isError
  const kindChanged = !loading && kind !== currentKind
  const resellerChanged = !loading && kind === 'REGULAR' && resellerId !== currentReseller
  const changed = kindChanged || resellerChanged
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: changed, disabled: saving, onDiscard: onClose, protectUnload: true })
  const associated = assignmentQuery.data?.associatedCustomersCount ?? 0
  const commissionPlan = assignmentQuery.data?.commissionPlan ?? null
  const pendingCommissions = assignmentQuery.data?.pendingCommissionCount ?? 0
  // A reseller cannot have a reseller. It keeps the mark while it has associated customers, a commission plan or unsettled commissions.
  const canBecomeReseller = !currentReseller
  const canStopBeingReseller = currentKind !== 'RESELLER' || (associated === 0 && !commissionPlan && pendingCommissions === 0)
  const steps = [
    associated > 0 && `reasigná ${associated === 1 ? 'su cliente asociado' : `sus ${associated} clientes asociados`}`,
    commissionPlan && `quitale el plan de comisión «${commissionPlan}»`,
    pendingCommissions > 0 && 'liquidá sus comisiones pendientes',
  ].filter((step): step is string => Boolean(step))
  const keepsMark = steps.length > 1 ? `${steps.slice(0, -1).join(', ')} y ${steps[steps.length - 1]}` : steps[0]
  const options = (resellersQuery.data?.content ?? []).filter(reseller => reseller.id !== customer.id).map(reseller => ({ id: reseller.id, name: reseller.name }))
  const pending = assignmentQuery.data?.pendingOrders ?? []
  const pendingCount = assignmentQuery.data?.pendingOrdersCount ?? 0

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!changed || saving || loading || failed) return
    setSaving(true); setError('')
    try {
      if (kindChanged) {
        await apiPatch(`/api/customers/${customer.id}/kind`, { kind })
        await onSaved(kind === 'RESELLER' ? `${customer.name} ahora es revendedor.` : `${customer.name} dejó de ser revendedor.`)
      } else {
        await apiPatch(`/api/customers/${customer.id}/reseller`, { resellerId: resellerId || null })
        await onSaved(resellerId ? 'Revendedor asignado. Los pedidos nuevos del cliente se cargan a su cuenta.' : 'Revendedor quitado. Los pedidos nuevos se cargan a la cuenta del cliente.')
      }
    } catch (cause) {
      setError(cause instanceof ApiError && cause.status === 409 ? cause.detail || 'Los datos del revendedor cambiaron. Cerrá y volvé a intentarlo.'
        : cause instanceof ApiError && cause.status === 403 ? 'No tenés permisos para gestionar revendedores.'
          : cause instanceof Error ? cause.message : 'No se pudo guardar el revendedor.')
    } finally { setSaving(false) }
  }

  return <>
    <FormModal label={`Revendedor de ${customer.name}`} size="compact" busy={saving} onClose={requestDiscard}>
      <Panel title="Revendedor del cliente" description={customer.name}><form onSubmit={save}>
        <fieldset className="m-0 border-0 p-0" disabled={saving || loading || failed}>
          <legend className="text-[11px] font-bold text-ink mb-[6px]">Tipo de cliente</legend>
          <label className="radio-row min-h-[44px] text-ink"><input type="radio" name={`${id}-kind`} value="REGULAR" checked={kind === 'REGULAR'} disabled={!canStopBeingReseller}
            aria-describedby={!canStopBeingReseller ? `${id}-regular-help` : undefined} onChange={() => setKind('REGULAR')} />Cliente</label>
          <label className="radio-row min-h-[44px] text-ink"><input type="radio" name={`${id}-kind`} value="RESELLER" checked={kind === 'RESELLER'} disabled={!canBecomeReseller}
            aria-describedby={!canBecomeReseller ? `${id}-reseller-help` : undefined} onChange={() => setKind('RESELLER')} />Revendedor</label>
          {!canStopBeingReseller && <p className="helper-text" id={`${id}-regular-help`}>Para quitar la marca, {keepsMark}.</p>}
          {!canBecomeReseller && <p className="helper-text" id={`${id}-reseller-help`}>Para marcarlo como revendedor, primero quitale su revendedor y guardá.</p>}
        </fieldset>
        {kind === 'RESELLER'
          ? <p className="helper-text">{currentKind === 'RESELLER' ? 'Los pedidos de sus clientes asociados se cargan a su cuenta corriente.' : 'Podrás asociarle clientes: sus pedidos nuevos se cargarán a esta cuenta corriente.'} Los cobros de un revendedor los registra la administración.</p>
          : <>
            {currentKind === 'RESELLER' && <p className="helper-text">Dejará de ser revendedor. Su cuenta corriente y la deuda que tiene a cargo se conservan.</p>}
            {currentKind !== 'RESELLER' && <div className="mt-[14px]">
              <CustomerSelect mode="selection" label="Revendedor" emptyLabel="Sin revendedor (cuenta propia)" options={options} value={resellerId}
                onChange={setResellerId} loading={loading} disabled={saving || failed} describedBy={`${id}-assignment-help`} />
              <p className="helper-text" id={`${id}-assignment-help`}>Solo cambian los pedidos nuevos: la deuda existente y los pedidos ya confirmados conservan su cuenta.</p>
            </div>}
          </>}
        {failed && <p className="error-text" role="alert">No se pudieron cargar los datos del revendedor. <Button type="button" variant="link" onClick={() => { void assignmentQuery.refetch(); void resellersQuery.refetch() }}>Reintentar</Button></p>}
        {resellerChanged && pendingCount > 0 && <div className="warning-text mt-[12px]" role="status">
          <p className="m-0">{pendingCount === 1 ? 'Hay 1 pedido sin entregar que seguirá' : `Hay ${pendingCount} pedidos sin entregar que seguirán`} en la cuenta actual. Para moverlos, cancelalos y volvé a cargarlos.</p>
          <ul className="m-0 mt-[6px] pl-[18px]">{pending.map(order => <li key={order.id}>Pedido {order.number} · <CurrencyText>{money(order.total)}</CurrencyText> · cuenta {order.account}</li>)}</ul>
          {pendingCount > pending.length && <p className="m-0 mt-[6px]">Se muestran los {pending.length} más recientes.</p>}
        </div>}
        {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
        <div className="page-actions mt-[20px] justify-end"><Button type="button" variant="secondary" disabled={saving} onClick={requestDiscard}>Cancelar</Button><Button disabled={!changed || saving || loading || failed}>{saving ? 'Guardando...' : 'Guardar cambios'}</Button></div>
      </form></Panel>
    </FormModal>
    {discardDialog}
  </>
}
