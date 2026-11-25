import { Pagination } from '../../shared/components/Pagination'
import { formatBusinessDateTime as date } from '../../shared/dates'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { queryKeys } from '../../shared/api/queryKeys'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { auditOperations, auditResults, auditSearchTerm } from './auditLabels'

type AuditEvent = {
  id: string
  actorUserId?: string | null
  actor?: string | null
  operation: string
  resourceType: string
  resourceId?: string | null
  result: string
  correlationId: string
  details: string
  createdAt: string
}


function AuditIdentifier({ value, label }: { value: string; label: string }) {
  if (!value) return <span>—</span>
  if (value.length <= 16) return <span>{value}</span>

  return <details className="audit-identifier">
    <summary title={value} aria-label={label}><span>{value}</span></summary>
    <code>{value}</code>
  </details>
}

export default function AuditPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawPage = Number(searchParams.get('page') ?? 0)
  const page = Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 0
  const search = searchParams.get('search') ?? ''
  const size = 20
  const path = `/api/audit?page=${page}&size=${size}&search=${encodeURIComponent(auditSearchTerm(search))}`
  const query = useQuery({ queryKey: queryKeys.audit.list(page, search), queryFn: ({ signal }) => apiGet<ApiPage<AuditEvent>>(path, signal) })
  const columns: TableColumn[] = [
    { key: 'createdAt', label: 'Fecha' },
    { key: 'actor', label: 'Usuario', emphasis: true },
    { key: 'operation', label: 'Operación', render: (value) => <span title={value}>{auditOperations[value] ?? value}</span> },
    { key: 'resource', label: 'Recurso', render: (value, row) => <div className="grid min-w-0 gap-[4px]">
      <span>{value}</span>
      {row.resourceId && <AuditIdentifier value={row.resourceId} label={'ID de ' + value + ': ' + row.resourceId} />}
    </div> },
    { key: 'result', label: 'Resultado', render: (value) => <span title={value}>{auditResults[value] ?? value}</span> },
    { key: 'correlationId', label: 'ID de solicitud', render: (value) => <AuditIdentifier value={value} label={'ID de solicitud: ' + value} /> },
  ]
  const rows = (query.data?.content ?? []).map((event) => ({
    id: event.id,
    createdAt: date(event.createdAt),
    actor: event.actor || event.actorUserId || 'Sistema',
    operation: event.operation,
    resource: event.resourceType,
    resourceId: event.resourceId ?? '',
    result: event.result,
    correlationId: event.correlationId,
  }))

  function update(params: { page?: number; search?: string }) {
    const next = new URLSearchParams(searchParams)
    if (params.search !== undefined) {
      if (params.search) next.set('search', params.search)
      else next.delete('search')
      next.delete('page')
    }
    if (params.page !== undefined) {
      if (params.page > 0) next.set('page', String(params.page))
      else next.delete('page')
    }
    setSearchParams(next, { replace: params.search !== undefined })
  }

  return <>
    <PageHeader title="Auditoría" description="Consultá los eventos append-only con actor, operación y recurso." />
    <Panel>
      <div className="toolbar"><label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Filtrar eventos</span><input className="input search-input audit-search-input" placeholder="Operación, recurso, ID o resultado" aria-label="Filtrar eventos de auditoría" value={search} onChange={(event) => update({ search: event.target.value })} /></label></div>
      {query.isLoading ? <EmptyState state="loading" title="Cargando auditoría" description="Consultando eventos registrados." /> : query.isError ? <EmptyState state="error" title="No se pudo cargar la auditoría" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} /> : rows.length === 0 ? <EmptyState title="No hay eventos para mostrar" description="Probá otra búsqueda." /> : <>
        <DataTable className="compact-list-table audit-events-table" columns={columns} rows={rows} />
        <Pagination summary={<>Página {page + 1} · {query.data?.totalElements ?? 0} eventos</>} onPrevious={() => update({ page: page - 1 })} onNext={() => update({ page: page + 1 })} previousDisabled={page === 0} nextDisabled={page + 1 >= (query.data?.totalPages ?? 0)} />
      </>}
    </Panel>
  </>
}
