import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPut, ApiError } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { CommissionPlanForm, commissionPlansPath, type CommissionPlan } from './CommissionPlanForm'

type Overview = { id: string; name: string; sellerCount: number; resellerCount: number; ruleCount: number }
type Seller = { id: string; displayName: string; status: string }
type Reseller = { id: string; name: string; status: string }
type Assignment = { planId: string | null }
type AssignmentDraft = { planId: string | null; expectedPlanId: string | null }
type Editor = { mode: 'create' } | { mode: 'edit'; id: string }
type Option = { id: string; name: string; status: string }
const plansKey = [commissionPlansPath]
const sellersPath = '/api/sellers?page=0&size=100'
const resellersPath = '/api/customers?page=0&size=100&kind=RESELLER&status='

/** Sellers and resellers get plans the same way; resellers are customers, so their picker is CustomerSelect. */
const beneficiaries = {
  seller: {
    title: 'Asignación a vendedores', description: 'Elegí quién tiene comisión. Podés usar el mismo plan con varios vendedores.',
    label: 'Vendedor', planLabel: 'Plan del vendedor', list: 'la lista de vendedores', assignments: 'assignments', Select: SellerSelect,
    load: async (): Promise<Option[]> => (await apiGetAllPages<Seller>(sellersPath)).content.map(seller => ({ id: seller.id, name: seller.displayName, status: seller.status })),
    key: sellersPath,
  },
  reseller: {
    title: 'Asignación a revendedores',
    description: 'El revendedor cobra comisión por las ventas cargadas a su cuenta: pedidos de sus clientes y compras propias.',
    label: 'Revendedor', planLabel: 'Plan del revendedor', list: 'la lista de revendedores', assignments: 'reseller-assignments', Select: CustomerSelect,
    load: async (): Promise<Option[]> => (await apiGetAllPages<Reseller>(resellersPath)).content.map(reseller => ({ id: reseller.id, name: reseller.name, status: reseller.status })),
    key: resellersPath,
  },
}

function CommissionAssignment({ beneficiary, plans, onSaved }: { beneficiary: keyof typeof beneficiaries; plans: Overview[]; onSaved: () => void }) {
  const config = beneficiaries[beneficiary]
  const client = useQueryClient()
  const [selected, setSelected] = useState('')
  const [draft, setDraft] = useState<AssignmentDraft>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const options = useQuery({ queryKey: [config.key, 'commission-options'], queryFn: config.load })
  const path = `${commissionPlansPath}/${config.assignments}/${selected}`
  const assignment = useQuery({ queryKey: [path], queryFn: () => apiGet<Assignment>(path), enabled: Boolean(selected) })
  const Select = config.Select

  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy || conflict || !draft || draft.planId === draft.expectedPlanId) return
    setBusy(true)
    setError('')
    try {
      const saved = await apiPut<Assignment>(path, draft)
      client.setQueryData([path], saved)
      setDraft(undefined)
      onSaved()
    } catch (cause) {
      const changed = cause instanceof ApiError && cause.status === 409
      setConflict(changed)
      setError(changed ? 'La asignación cambió mientras la editabas. Volvé a cargarla.' : cause instanceof Error ? cause.message : 'No se pudo guardar la asignación.')
    } finally {
      setBusy(false)
    }
  }

  function choose(id: string) {
    setSelected(id)
    setDraft(undefined)
    setError('')
    setConflict(false)
  }

  return <Panel title={config.title} description={config.description}>
    <form onSubmit={save} aria-label={config.title}>
      <div className="form-grid mb-[14px]">
        <Select mode="selection" label={config.label} options={(options.data ?? []).map((item) => ({
          id: item.id, name: item.status === 'INACTIVE' ? `${item.name} (inactivo)` : item.name,
        }))} value={selected} onChange={choose} loading={options.isPending} disabled={busy || options.isError} />
        {assignment.isSuccess && <label className="field"><span>{config.planLabel}</span>
          <select className="select min-w-0 w-full" value={(draft ? draft.planId : assignment.data.planId) ?? ''} disabled={busy || conflict}
            onChange={(event) => setDraft({ planId: event.target.value || null, expectedPlanId: draft ? draft.expectedPlanId : assignment.data.planId })}>
            <option value="">Sin comisión</option>
            {plans.map((plan) => <option value={plan.id} key={plan.id}>{plan.name}</option>)}
          </select>
        </label>}
      </div>
      {selected && assignment.isPending && <p className="helper-text" role="status">Cargando asignación…</p>}
      {(options.isError || selected && assignment.isError) && <div className="page-actions">
        <p className="error-text" role="alert">No se pudo cargar {options.isError ? config.list : 'la asignación'}.</p>
        <Button type="button" variant="secondary" onClick={() => void (options.isError ? options.refetch() : assignment.refetch())}>Reintentar</Button>
      </div>}
      {error && <p className="error-text" role="alert">{error}</p>}
      {conflict ? <Button type="button" variant="secondary" disabled={assignment.isFetching} onClick={async () => {
        const refreshed = await assignment.refetch()
        if (!refreshed.isSuccess) return
        setDraft(undefined)
        setConflict(false)
        setError('')
      }}>Volver a cargar asignación</Button> : <Button type="submit" disabled={busy || !assignment.isSuccess || !draft || draft.planId === draft.expectedPlanId}>
        {busy ? 'Guardando...' : 'Guardar asignación'}
      </Button>}
    </form>
  </Panel>
}

export default function CommissionPlansPage() {
  const client = useQueryClient()
  const plans = useQuery({ queryKey: plansKey, queryFn: () => apiGet<Overview[]>(commissionPlansPath) })
  const [editor, setEditor] = useState<Editor>()
  const [feedback, setFeedback] = useState('')
  const editId = editor?.mode === 'edit' ? editor.id : ''
  const detail = useQuery({
    queryKey: [commissionPlansPath, editId], queryFn: ({ signal }) => apiGet<CommissionPlan>(`${commissionPlansPath}/${editId}`, signal),
    enabled: Boolean(editId), gcTime: 0,
  })

  function refresh(message: string) {
    setFeedback(message)
    void client.invalidateQueries({ queryKey: plansKey })
  }

  const columns: TableColumn[] = [
    { key: 'name', label: 'Plan', emphasis: true, render: (name) => <span className="[overflow-wrap:anywhere]">{name}</span> },
    { key: 'ruleCount', label: 'Reglas', align: 'right' },
    { key: 'sellerCount', label: 'Vendedores asignados', align: 'right' },
    { key: 'resellerCount', label: 'Revendedores asignados', align: 'right' },
    { key: 'actions', label: '', render: (_value, row) => <div className="table-row-actions">
      <Button variant="link" aria-label={`Editar plan ${row.name}`} onClick={() => { setFeedback(''); setEditor({ mode: 'edit', id: row.id }) }}>Editar</Button>
    </div> },
  ]

  return <>
    <PageHeader eyebrow="Administración" title="Comisiones" actions={<>
      <Button href="/admin/settings/commissions/settlements">Liquidación de comisiones</Button>
      <Button variant="secondary" href="/admin/settings">Volver a configuración</Button>
    </>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    <div className="content-grid">
      <Panel title="Planes de comisión" description="Guardá reglas fijas o porcentuales y reutilizalas para distintos vendedores y revendedores."
        action={<Button onClick={() => { setFeedback(''); setEditor({ mode: 'create' }) }}>+ Nuevo plan</Button>}>
        {plans.isPending ? <div role="status"><EmptyState title="Cargando planes" description="Consultando la configuración de comisiones." /></div>
          : plans.isError ? <div role="alert"><EmptyState title="No se pudieron cargar los planes"
            description={plans.error.message}
            action={<Button variant="secondary" onClick={() => void plans.refetch()}>Reintentar</Button>} /></div>
            : <DataTable columns={columns} rows={plans.data.map((plan) => ({
              id: plan.id, name: plan.name, ruleCount: String(plan.ruleCount), sellerCount: String(plan.sellerCount),
              resellerCount: String(plan.resellerCount ?? 0),
            }))} emptyContent={<p className="table-empty-message">Todavía no hay planes de comisión.</p>} />}
        {editId && detail.isFetching && !detail.data && <p className="helper-text" role="status">Cargando plan…</p>}
        {editId && detail.isError && !detail.data && <div className="page-actions">
          <p className="error-text" role="alert">No se pudo cargar el plan.</p>
          <Button variant="secondary" onClick={() => void detail.refetch()}>Reintentar</Button>
        </div>}
      </Panel>
      {plans.isSuccess && <CommissionAssignment beneficiary="seller" plans={plans.data} onSaved={() => refresh('Asignación guardada.')} />}
      {plans.isSuccess && <CommissionAssignment beneficiary="reseller" plans={plans.data} onSaved={() => refresh('Asignación guardada.')} />}
    </div>
    {editor && (editor.mode === 'create' || detail.data) && <CommissionPlanForm key={editId}
      plan={editor.mode === 'edit' ? detail.data : undefined} onClose={() => setEditor(undefined)}
      onSaved={() => { setEditor(undefined); refresh('Plan guardado.') }} />}
  </>
}
