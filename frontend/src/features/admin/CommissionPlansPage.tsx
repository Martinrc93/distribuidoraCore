import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPut, ApiError } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { SellerSelect } from '../../shared/components/EntitySelect'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { CommissionPlanForm, commissionPlansPath, type CommissionPlan } from './CommissionPlanForm'

type Overview = { id: string; name: string; sellerCount: number; ruleCount: number }
type Seller = { id: string; displayName: string; status: string }
type Assignment = { sellerId: string; planId: string | null }
type AssignmentDraft = { planId: string | null; expectedPlanId: string | null }
type Editor = { mode: 'create' } | { mode: 'edit'; id: string }
const plansKey = [commissionPlansPath]
const sellersPath = '/api/sellers?page=0&size=100'

function SellerCommissionAssignment({ plans, onSaved }: { plans: Overview[]; onSaved: () => void }) {
  const client = useQueryClient()
  const [seller, setSeller] = useState('')
  const [draft, setDraft] = useState<AssignmentDraft>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const sellers = useQuery({ queryKey: [sellersPath], queryFn: () => apiGetAllPages<Seller>(sellersPath) })
  const path = `${commissionPlansPath}/assignments/${seller}`
  const assignment = useQuery({ queryKey: [path], queryFn: () => apiGet<Assignment>(path), enabled: Boolean(seller) })

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

  function changeSeller(id: string) {
    setSeller(id)
    setDraft(undefined)
    setError('')
    setConflict(false)
  }

  return <Panel title="Asignación a vendedores" description="Elegí quién tiene comisión. Podés usar el mismo plan con varios vendedores.">
    <form onSubmit={save}>
      <div className="form-grid mb-[14px]">
        <SellerSelect mode="selection" label="Vendedor" options={(sellers.data?.content ?? []).map((item) => ({
          id: item.id, name: item.status === 'INACTIVE' ? `${item.displayName} (inactivo)` : item.displayName,
        }))} value={seller} onChange={changeSeller} loading={sellers.isPending} disabled={busy || sellers.isError} />
        {assignment.isSuccess && <label className="field"><span>Plan de comisión</span>
          <select className="select min-w-0 w-full" value={(draft ? draft.planId : assignment.data.planId) ?? ''} disabled={busy || conflict}
            onChange={(event) => setDraft({ planId: event.target.value || null, expectedPlanId: draft ? draft.expectedPlanId : assignment.data.planId })}>
            <option value="">Sin comisión</option>
            {plans.map((plan) => <option value={plan.id} key={plan.id}>{plan.name}</option>)}
          </select>
        </label>}
      </div>
      {seller && assignment.isPending && <p className="helper-text" role="status">Cargando asignación…</p>}
      {(sellers.isError || seller && assignment.isError) && <div className="page-actions">
        <p className="error-text" role="alert">No se pudo cargar {sellers.isError ? 'la lista de vendedores' : 'la asignación'}.</p>
        <Button type="button" variant="secondary" onClick={() => void (sellers.isError ? sellers.refetch() : assignment.refetch())}>Reintentar</Button>
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
      <Panel title="Planes de comisión" description="Guardá reglas fijas o porcentuales y reutilizalas para distintos vendedores."
        action={<Button onClick={() => { setFeedback(''); setEditor({ mode: 'create' }) }}>+ Nuevo plan</Button>}>
        {plans.isPending ? <div role="status"><EmptyState title="Cargando planes" description="Consultando la configuración de comisiones." /></div>
          : plans.isError ? <div role="alert"><EmptyState title="No se pudieron cargar los planes"
            description={plans.error.message}
            action={<Button variant="secondary" onClick={() => void plans.refetch()}>Reintentar</Button>} /></div>
            : <DataTable columns={columns} rows={plans.data.map((plan) => ({
              id: plan.id, name: plan.name, ruleCount: String(plan.ruleCount), sellerCount: String(plan.sellerCount),
            }))} emptyContent={<p className="table-empty-message">Todavía no hay planes de comisión.</p>} />}
        {editId && detail.isFetching && !detail.data && <p className="helper-text" role="status">Cargando plan…</p>}
        {editId && detail.isError && !detail.data && <div className="page-actions">
          <p className="error-text" role="alert">No se pudo cargar el plan.</p>
          <Button variant="secondary" onClick={() => void detail.refetch()}>Reintentar</Button>
        </div>}
      </Panel>
      {plans.isSuccess && <SellerCommissionAssignment plans={plans.data} onSaved={() => refresh('Asignación guardada.')} />}
    </div>
    {editor && (editor.mode === 'create' || detail.data) && <CommissionPlanForm key={editId}
      plan={editor.mode === 'edit' ? detail.data : undefined} onClose={() => setEditor(undefined)}
      onSaved={() => { setEditor(undefined); refresh('Plan guardado.') }} />}
  </>
}
