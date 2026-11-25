import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { apiGet } from '../../shared/api/client'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { Panel } from '../../shared/components/Panel'
import { CommissionAssignments } from './CommissionAssignments'
import { CommissionPlanForm, commissionPlansPath, type CommissionPlan } from './CommissionPlanForm'
import { SettingsPageHeader } from './SettingsPageHeader'
import { commissionSettlementsPath } from './settingsPaths'

type Overview = { id: string; name: string; sellerCount: number; resellerCount: number; ruleCount: number }
type Editor = { mode: 'create' } | { mode: 'edit'; id: string }

export default function CommissionPlansPage() {
  const client = useQueryClient()
  const plans = useQuery({ queryKey: queryKeys.commissions.plans, queryFn: ({ signal }) => apiGet<Overview[]>(commissionPlansPath, signal) })
  const [editor, setEditor] = useState<Editor>()
  const [feedback, setFeedback] = useState('')
  const editId = editor?.mode === 'edit' ? editor.id : ''
  const detail = useQuery({
    queryKey: queryKeys.commissions.plan(editId), queryFn: ({ signal }) => apiGet<CommissionPlan>(`${commissionPlansPath}/${editId}`, signal),
    enabled: Boolean(editId), gcTime: 0,
  })

  function refresh(message: string) {
    setFeedback(message)
    void invalidateDomains(client, 'commissions')
  }

  const columns: TableColumn[] = [
    { key: 'name', label: 'Plan', emphasis: true, render: (name) => <span className="[overflow-wrap:anywhere]">{name}</span> },
    { key: 'ruleCount', label: 'Reglas', align: 'right' },
    { key: 'sellerCount', label: 'Vendedores asignados', align: 'right' },
    { key: 'resellerCount', label: 'Revendedores asignados', align: 'right' },
    { key: 'actions', label: 'Acciones', render: (_value, row) => <div className="table-row-actions">
      <Button variant="link" aria-label={`Editar plan ${row.name}`} onClick={() => { setFeedback(''); setEditor({ mode: 'edit', id: row.id }) }}>Editar</Button>
    </div> },
  ]

  return <>
    <SettingsPageHeader title="Planes de comisiones" description="Definí porcentajes y asigná planes a vendedores y revendedores."
      actions={<Link className="button button-secondary" to={commissionSettlementsPath}>Liquidación de comisiones</Link>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    <div className="content-grid">
      <Panel title="Planes de comisión" description="Guardá reglas fijas o porcentuales y reutilizalas para distintos vendedores y revendedores."
        action={<Button onClick={() => { setFeedback(''); setEditor({ mode: 'create' }) }}>+ Nuevo plan</Button>}>
        {plans.isPending ? <EmptyState state="loading" title="Cargando planes" description="Consultando la configuración de comisiones." />
          : plans.isError ? <EmptyState state="error" title="No se pudieron cargar los planes"
            description={plans.error.message}
            action={<Button variant="secondary" onClick={() => void plans.refetch()}>Reintentar</Button>} />
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
      {plans.isSuccess && <CommissionAssignments plans={plans.data} />}
    </div>
    {editor && (editor.mode === 'create' || detail.data) && <CommissionPlanForm key={editId}
      plan={editor.mode === 'edit' ? detail.data : undefined} onClose={() => setEditor(undefined)}
      onSaved={() => { setEditor(undefined); refresh('Plan guardado.') }} />}
  </>
}
