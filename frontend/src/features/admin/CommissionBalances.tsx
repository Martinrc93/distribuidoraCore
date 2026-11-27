import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { queryKeys } from '../../shared/api/queryKeys'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { Pagination } from '../../shared/components/Pagination'
import { Panel } from '../../shared/components/Panel'
import { formatMoney } from '../../shared/format'
import { useUrlListState } from '../../shared/useUrlListState'

type Balance = {
  id: string
  beneficiary: 'SELLER' | 'RESELLER'
  name: string
  pendingAmount: string
  paidAmount: string
  pendingCount: number
  paidCount: number
}

const columns: TableColumn[] = [
  { key: 'name', label: 'Beneficiario', emphasis: true },
  { key: 'type', label: 'Tipo' },
  { key: 'status', label: 'Estado', render: (status, row) => <Badge tone={row.tone === 'strong' ? 'strong' : 'soft'}>{status}</Badge> },
  { key: 'pending', label: 'Pendiente', align: 'right' },
  { key: 'paid', label: 'Pagado', align: 'right' },
  { key: 'detail', label: 'Acciones', render: (detail, row) =>
    <Button variant="link" href={detail} aria-label={`Ver movimientos de ${row.name}`}>Ver movimientos</Button> },
]

function paymentStatus(balance: Balance) {
  if (balance.pendingCount === 0) return 'Pagado'
  if (Number(balance.pendingAmount) <= 0) return 'Ajuste pendiente'
  return balance.paidCount > 0 ? 'Pagado y pendiente' : 'Pendiente'
}

export function CommissionBalances() {
  const [params] = useSearchParams()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['status'], 20, 'summaryPage')
  const filter = getFilter('status')
  const status = filter === 'PENDING' || filter === 'PAID' ? filter : 'ALL'
  const query = new URLSearchParams({ status, page: String(page), size: String(pageSize) })
  const balances = useQuery({
    queryKey: [...queryKeys.commissions.balances, status, page, pageSize],
    queryFn: ({ signal }) => apiGet<ApiPage<Balance>>(`/api/commissions/balances?${query}`, signal),
  })

  return <Panel title="Resumen de pagos" description="Importes acumulados por beneficiario.">
    <div className="toolbar">
      <label className="field max-[640px]:w-full">
        <span>Estado de pago</span>
        <select className="select" value={status === 'ALL' ? '' : status} onChange={(event) => setFilter('status', event.target.value)}>
          <option value="">Todos</option>
          <option value="PENDING">Con pendientes</option>
          <option value="PAID">Pagados</option>
        </select>
      </label>
    </div>
    {balances.isPending && <EmptyState state="loading" title="Cargando resumen de pagos"
      description="Consultando los importes de vendedores y revendedores." compact />}
    {balances.isError && <EmptyState state="error" title="No se pudo cargar el resumen de pagos"
      description={balances.error.message} compact
      action={<Button variant="secondary" onClick={() => void balances.refetch()}>Reintentar</Button>} />}
    {balances.isSuccess && <>
      <DataTable columns={columns} rows={balances.data.content.map(balance => {
        const seller = balance.beneficiary === 'SELLER'
        const detail = new URLSearchParams(params)
        detail.set('type', seller ? 'seller' : 'reseller')
        detail.delete('sellerId')
        detail.delete('resellerId')
        detail.delete('page')
        detail.set(seller ? 'sellerId' : 'resellerId', balance.id)
        return {
          id: `${balance.beneficiary}-${balance.id}`,
          name: balance.name,
          type: seller ? 'Vendedor' : 'Revendedor',
          status: paymentStatus(balance),
          tone: balance.pendingCount === 0 ? 'strong' : 'soft',
          pending: formatMoney(balance.pendingAmount, 4),
          paid: formatMoney(balance.paidAmount, 4),
          detail: `/commission-settlements?${detail}`,
        }
      })} emptyContent={<p className="table-empty-message">
        {status === 'ALL' && page === 0 ? 'Todavía no hay movimientos de comisión.' : 'No hay beneficiarios en este resultado.'}
      </p>} />
      {(balances.data.totalPages > 1 || page > 0) && <Pagination label="Paginación del resumen de pagos"
        summary={<>Página {page + 1} · {balances.data.totalElements} {balances.data.totalElements === 1 ? 'beneficiario' : 'beneficiarios'}</>}
        onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)}
        previousDisabled={page === 0} nextDisabled={page + 1 >= balances.data.totalPages} />}
    </>}
  </Panel>
}
