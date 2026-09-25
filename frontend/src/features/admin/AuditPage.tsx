import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

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

const date = (value: string) => new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))

export default function AuditPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = Math.max(0, Number(searchParams.get('page') || 0) || 0)
  const search = searchParams.get('search') ?? ''
  const size = 20
  const path = `/api/audit?page=${page}&size=${size}&search=${encodeURIComponent(search.trim())}`
  const query = useQuery({ queryKey: ['audit-events', page, search], queryFn: () => apiGet<ApiPage<AuditEvent>>(path) })
  const columns: TableColumn[] = [
    { key: 'createdAt', label: 'Fecha' },
    { key: 'actor', label: 'Usuario', emphasis: true },
    { key: 'operation', label: 'Operación' },
    { key: 'resource', label: 'Recurso' },
    { key: 'result', label: 'Resultado' },
    { key: 'correlationId', label: 'Request ID' },
  ]
  const rows = (query.data?.content ?? []).map((event) => ({
    id: event.id,
    createdAt: date(event.createdAt),
    actor: event.actor || event.actorUserId || 'Sistema',
    operation: event.operation,
    resource: `${event.resourceType}${event.resourceId ? ` · ${event.resourceId}` : ''}`,
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
    setSearchParams(next)
  }

  return <>
    <PageHeader eyebrow="Administración" title="Auditoría" description="Consultá los eventos append-only con actor, operación y recurso." />
    <Panel>
      <div className="toolbar"><label className="field"><span>Filtrar eventos</span><input className="input search-input" placeholder="Operación, recurso, ID o resultado" aria-label="Filtrar eventos de auditoría" value={search} onChange={(event) => update({ search: event.target.value })} /></label></div>
      {query.isLoading ? <EmptyState title="Cargando auditoría" description="Consultando eventos registrados." /> : query.isError ? <EmptyState title="No se pudo cargar la auditoría" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay eventos para mostrar" description="Probá otra búsqueda." /> : <>
        <DataTable columns={columns} rows={rows} />
        <div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} eventos</span><div><Button variant="secondary" onClick={() => update({ page: page - 1 })} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => update({ page: page + 1 })} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div>
      </>}
    </Panel>
  </>
}
