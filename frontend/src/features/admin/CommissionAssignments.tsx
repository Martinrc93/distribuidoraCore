import { Pagination } from '../../shared/components/Pagination'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState, type FormEvent } from 'react'
import { apiGet, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { Panel } from '../../shared/components/Panel'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { useUrlListState } from '../../shared/useUrlListState'
import { commissionPlansPath } from './CommissionPlanForm'

type BeneficiaryType = 'SELLER' | 'RESELLER'
type Selection = { type: BeneficiaryType; id: string }
type Assignment = { planId: string | null }
type AssignmentDraft = Assignment & { expectedPlanId: string | null }
type AssignedPlan = { type: BeneficiaryType; beneficiaryId: string; name: string; status: string; planName: string }
type PlanOption = { id: string; name: string }
type Seller = { id: string; displayName: string; status: string }
type Reseller = { id: string; name: string; status: string }

const assignmentsPath = `${commissionPlansPath}/assignments`
const sellersPath = '/api/sellers?page=0&size=100'
const resellersPath = '/api/customers?page=0&size=100&kind=RESELLER&status='

const beneficiaries = {
  SELLER: {
    label: 'Vendedor', optionsPath: sellersPath, assignmentsPath, Select: SellerSelect,
    load: async (signal?: AbortSignal) => (await apiGetAllPages<Seller>(sellersPath, signal)).content.map(seller => ({ ...seller, name: seller.displayName })),
  },
  RESELLER: {
    label: 'Revendedor', optionsPath: resellersPath, assignmentsPath: `${commissionPlansPath}/reseller-assignments`, Select: CustomerSelect,
    load: async (signal?: AbortSignal) => (await apiGetAllPages<Reseller>(resellersPath, signal)).content,
  },
}

export function CommissionAssignments({ plans }: { plans: PlanOption[] }) {
  const client = useQueryClient()
  const typeInput = useRef<HTMLSelectElement>(null)
  const nextSelection = useRef<{ selection: Selection; focusForm: boolean }>(undefined)
  const [selection, setSelection] = useState<Selection>({ type: 'SELLER', id: '' })
  const [draft, setDraft] = useState<AssignmentDraft>()
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const config = beneficiaries[selection.type]
  const Select = config.Select
  const id = useId()
  const { page, pageSize, setPage } = useUrlListState([], 20, 'assignmentPage')
  const assigned = useQuery({
    queryKey: queryKeys.commissions.assignments(page, pageSize),
    queryFn: ({ signal }) => apiGet<ApiPage<AssignedPlan>>(`${assignmentsPath}?page=${page}&size=${pageSize}`, signal),
  })
  const options = useQuery({ queryKey: queryKeys.commissions.options(config.optionsPath), queryFn: ({ signal }) => config.load(signal) })
  const path = `${config.assignmentsPath}/${selection.id}`
  const assignmentKey = queryKeys.commissions.assignment(path)
  const assignment = useQuery({ queryKey: assignmentKey, queryFn: ({ signal }) => apiGet<Assignment>(path, signal), enabled: Boolean(selection.id) })
  const locked = busy || conflict && assignment.isFetching
  const hasChanges = Boolean(draft && draft.planId !== draft.expectedPlanId)
  const canChoosePlan = Boolean(selection.id) && assignment.isSuccess && plans.length > 0 && !busy && !conflict
  const planPlaceholder = plans.length === 0 ? 'Primero creá un plan'
    : !selection.id ? `Seleccioná un ${config.label.toLowerCase()}`
    : assignment.isPending ? 'Cargando asignación…'
    : assignment.isError ? 'Asignación no disponible' : undefined

  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges, disabled: locked, onDiscard: applySelection })

  function applySelection() {
    const next = nextSelection.current
    if (!next) return
    nextSelection.current = undefined
    setSelection(next.selection)
    setDraft(undefined)
    setSaved(false)
    setError('')
    setConflict(false)
    if (next.focusForm) requestAnimationFrame(() => typeInput.current?.focus())
  }

  function choose(next: Selection, focusForm = false) {
    if (next.type === selection.type && next.id === selection.id) {
      if (focusForm) typeInput.current?.focus()
      return
    }
    nextSelection.current = { selection: next, focusForm }
    requestDiscard()
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!canChoosePlan || !draft || !hasChanges) return
    setBusy(true)
    setError('')
    try {
      const result = await apiPut<Assignment>(path, draft)
      client.setQueryData(assignmentKey, result)
      setDraft(undefined)
      setSaved(true)
      void invalidateDomains(client, 'commissions')
    } catch (cause) {
      const changed = cause instanceof ApiError && cause.status === 409
      setConflict(changed)
      setError(changed ? 'La asignación cambió mientras la editabas. Volvé a cargarla.' : cause instanceof Error ? cause.message : 'No se pudo guardar la asignación.')
    } finally {
      setBusy(false)
    }
  }

  const columns: TableColumn[] = [
    { key: 'name', label: 'Beneficiario', emphasis: true },
    { key: 'type', label: 'Tipo', render: (type) => type === 'RESELLER' ? 'Revendedor' : 'Vendedor' },
    { key: 'planName', label: 'Plan' },
    { key: 'actions', label: 'Acciones', render: (_value, row) => <div className="table-row-actions">
      <Button type="button" variant="link" disabled={locked} aria-label={`Editar asignación de ${row.name}`}
        onClick={() => choose({ type: row.type === 'RESELLER' ? 'RESELLER' : 'SELLER', id: row.beneficiaryId }, true)}>Editar</Button>
    </div> },
  ]

  return <div className="content-grid">
    <Panel title="Asignar comisión">
      <form className="grid gap-[12px]" onSubmit={save} aria-label="Asignar comisión">
        <div className="commission-assignment-fields">
          <label className="field min-w-0">
            <span>Tipo de beneficiario</span>
            <select ref={typeInput} className="select min-w-0 w-full" value={selection.type} disabled={locked}
              onChange={(event) => choose({ type: event.target.value === 'RESELLER' ? 'RESELLER' : 'SELLER', id: '' })}>
              <option value="SELLER">Vendedor</option>
              <option value="RESELLER">Revendedor</option>
            </select>
          </label>
          <Select mode="selection" label={config.label} emptyLabel={`Seleccionar ${config.label.toLowerCase()}…`} options={(options.data ?? []).map((item) => ({
            id: item.id, name: item.status === 'INACTIVE' ? `${item.name} (inactivo)` : item.name,
          }))} value={selection.id} onChange={(id) => choose({ type: selection.type, id })} loading={options.isPending} disabled={locked || options.isError} />
          <div className="field min-w-0">
            <label htmlFor={id}>Plan de comisión</label>
            <select id={id} className="select min-w-0 w-full" value={(draft ? draft.planId : assignment.data?.planId) ?? ''}
              disabled={!canChoosePlan} onChange={(event) => {
                if (!assignment.isSuccess) return
                setDraft({ planId: event.target.value || null, expectedPlanId: draft ? draft.expectedPlanId : assignment.data.planId })
                setSaved(false)
                setError('')
              }}>
              <option value="">{planPlaceholder ?? 'Sin comisión'}</option>
              {plans.map((plan) => <option value={plan.id} key={plan.id}>{plan.name}</option>)}
            </select>
          </div>
          {conflict ? <Button type="button" variant="secondary" disabled={assignment.isFetching} onClick={async () => {
            const refreshed = await assignment.refetch()
            if (!refreshed.isSuccess) return
            setDraft(undefined)
            setConflict(false)
            setError('')
          }}>Volver a cargar asignación</Button> : <Button type="submit" disabled={!canChoosePlan || !hasChanges}>
            {busy ? 'Guardando...' : 'Guardar asignación'}
          </Button>}
        </div>
        {selection.type === 'RESELLER' && <p className="helper-text m-0">La comisión incluye las ventas a su cuenta por pedidos de sus clientes y compras propias.</p>}
        {(options.isError || selection.id && assignment.isError) && <div className="flex flex-wrap items-center gap-[8px]">
          <p className="error-text" role="alert">{options.isError ? 'No se pudo cargar la lista de beneficiarios.' : 'No se pudo cargar la asignación.'}</p>
          <Button type="button" variant="secondary" onClick={() => void (options.isError ? options.refetch() : assignment.refetch())}>Reintentar</Button>
        </div>}
        {error && <p className="error-text" role="alert">{error}</p>}
        <p className={saved ? 'success-text m-0' : 'sr-only'} role="status">
          {saved ? 'Asignación guardada.' : selection.id && assignment.isPending ? 'Cargando asignación…' : ''}
        </p>
      </form>
    </Panel>
    <Panel title="Planes asignados">
      {assigned.isPending ? <EmptyState compact state="loading" title="Cargando asignaciones" description="Consultando los planes asignados." />
        : assigned.isError ? <EmptyState compact state="error" title="No se pudieron cargar las asignaciones" description={assigned.error.message}
            action={<Button type="button" variant="secondary" onClick={() => void assigned.refetch()}>Reintentar</Button>} />
        : <>
          <DataTable className="compact-list-table commission-assignment-list" columns={columns} rows={assigned.data.content.map((row) => ({
            ...row, id: `${row.type}:${row.beneficiaryId}`, name: row.status === 'INACTIVE' ? `${row.name} (inactivo)` : row.name,
          }))} emptyContent={<p className="table-empty-message">{page > 0 ? 'No hay asignaciones en esta página.' : 'Todavía no hay planes asignados.'}</p>} />
          {(assigned.data.totalPages > 1 || page > 0) && <Pagination label="Paginación de planes asignados" summary={<>Página {page + 1} · {assigned.data.totalElements} {assigned.data.totalElements === 1 ? 'asignación' : 'asignaciones'}</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= assigned.data.totalPages} />}
        </>}
    </Panel>
    {discardDialog}
  </div>
}
