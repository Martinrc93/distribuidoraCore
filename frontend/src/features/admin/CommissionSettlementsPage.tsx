import { Pagination } from '../../shared/components/Pagination'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { queryKeys } from '../../shared/api/queryKeys'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { displayDate, formatBusinessDateShort, parseDate, presetRange, rangeError, todayInArgentina } from '../../shared/dates'
import { formatMoney } from '../../shared/format'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import { CommissionBalances } from './CommissionBalances'
import { commissionPlansPagePath } from './settingsPaths'

type Seller = { id: string; displayName: string; status: string }
type Reseller = { id: string; name: string; status: string }
type Beneficiary = 'seller' | 'reseller'
type Pending = { token: string; entryCount: number; amount: string }
type Movement = {
  id: string; orderId: string; orderNumber: string; customer: string; product: string; returned: boolean
  amount: string; occurredAt: string; paidAt: string | null
}
type Statement = { name: string; pending: Pending; periodAmount: string; movements: ApiPage<Movement> }
type Confirmation = { name: string; pending: Pending; path: string }
type Settlement = { id: string; amount: string }

const sellersPath = '/api/sellers?page=0&size=100'
const resellersPath = '/api/customers?page=0&size=100&kind=RESELLER&status='
const money = (value: unknown) => formatMoney(value, 4)
const beneficiaryLabels = {
  seller: { noun: 'vendedor', param: 'sellerId', api: 'sellers', list: 'la lista de vendedores' },
  reseller: { noun: 'revendedor', param: 'resellerId', api: 'resellers', list: 'la lista de revendedores' },
}
const movementCount = (count: number) => `${count} ${count === 1 ? 'movimiento' : 'movimientos'}`
const columns: TableColumn[] = [
  { key: 'date', label: 'Fecha' },
  { key: 'order', label: 'Pedido', render: (number, row) => <Button variant="link" href={`/orders/${row.orderId}`}>{number}</Button> },
  { key: 'customer', label: 'Cliente', render: (name) => <span className="[overflow-wrap:anywhere]">{name}</span> },
  { key: 'product', label: 'Producto', emphasis: true, render: (name) => <span className="[overflow-wrap:anywhere]">{name}</span> },
  { key: 'type', label: 'Movimiento' },
  { key: 'amount', label: 'Importe', align: 'right' },
  { key: 'payment', label: 'Estado', render: (value, row) => <Badge tone={row.paidAt ? 'strong' : 'soft'}>{value}</Badge> },
]

export default function CommissionSettlementsPage() {
  const client = useQueryClient()
  const [params, setParams] = useSearchParams()
  const [today] = useState(todayInArgentina)
  const [confirmation, setConfirmation] = useState<Confirmation>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)
  const { page, pageSize, setPage } = useUrlListState([], 20)
  const month = presetRange('month', today)
  const type = params.get('type')
  const beneficiary: Beneficiary = type === 'seller' || (type === null && params.has('sellerId')) ? 'seller' : 'reseller'
  const labels = beneficiaryLabels[beneficiary]
  const beneficiaryId = params.get(labels.param) ?? ''
  const dateMin = params.get('dateMin') ?? displayDate(month.start)
  const dateMax = params.get('dateMax') ?? displayDate(month.end)
  const from = parseDate(dateMin)
  const to = parseDate(dateMax)
  const dateError = rangeError(from, to, today)
  const path = `/api/commissions/${labels.api}/${encodeURIComponent(beneficiaryId)}`
  const query = new URLSearchParams({ dateMin: from ?? '', dateMax: to ?? '', page: String(page), size: String(pageSize) })
  const sellers = useQuery({ queryKey: queryKeys.sellers.options('settlements'), queryFn: ({ signal }) => apiGetAllPages<Seller>(sellersPath, signal), enabled: beneficiary === 'seller' })
  const resellers = useQuery({ queryKey: queryKeys.customers.resellers('settlements'), queryFn: ({ signal }) => apiGetAllPages<Reseller>(resellersPath, signal), enabled: beneficiary === 'reseller' })
  const choices = beneficiary === 'seller' ? sellers : resellers
  const statement = useQuery({
    queryKey: queryKeys.commissions.statement(path, query.toString()),
    queryFn: ({ signal }) => apiGet<Statement>(`${path}?${query}`, signal),
    enabled: Boolean(beneficiaryId) && !dateError,
    retry: false,
  })
  const ready = !dateError && beneficiaryId && statement.isSuccess ? statement.data : undefined

  function showBalances() {
    const next = new URLSearchParams(params)
    next.delete('sellerId')
    next.delete('resellerId')
    next.delete('page')
    setError('')
    setFeedback('')
    setParams(next)
  }

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    if (key === 'type') { next.delete('sellerId'); next.delete('resellerId') }
    next.delete('page')
    setFeedback('')
    setError('')
    setParams(next, { replace: true })
  }

  async function settle() {
    if (!confirmation || saving) return
    setSaving(true)
    setError('')
    try {
      const result = await apiPost<Settlement>(`${confirmation.path}/settlements`, { token: confirmation.pending.token })
      setConfirmation(undefined)
      setFeedback(`Pago de ${money(result.amount)} registrado para ${confirmation.name}.`)
    } catch (cause) {
      const changed = cause instanceof ApiError && cause.status === 409
      setError(changed ? 'El saldo pendiente cambió. Revisá el nuevo importe antes de registrar el pago.'
        : cause instanceof Error ? cause.message : 'No se pudo registrar el pago.')
      if (changed) {
        setConfirmation(undefined)
      } else {
        setSaving(false)
        return
      }
    }
    try {
      await Promise.all([
        client.invalidateQueries({ queryKey: queryKeys.commissions.statement(confirmation.path) }),
        client.invalidateQueries({ queryKey: queryKeys.commissions.balances }),
      ])
    } finally {
      setSaving(false)
    }
  }

  return <>
    <PageHeader title="Liquidación de comisiones"
      actions={<>
        {beneficiaryId && <Button variant="secondary" onClick={showBalances} disabled={saving}>Volver al resumen</Button>}
        <Link className="button button-secondary" to={commissionPlansPagePath}>Planes de comisiones</Link>
      </>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && !confirmation && <p className="error-text" role="alert">{error}</p>}
    <div className="content-grid">
      {!beneficiaryId && <CommissionBalances />}
      <Panel title="Beneficiario y período">
        <div className="form-grid commission-settlement-fields mb-0">
          <label className="field"><span>Comisiones de</span>
            <select className="select min-w-0 w-full" value={beneficiary} disabled={saving} onChange={(event) => setFilter('type', event.target.value)}>
              <option value="seller">Vendedores</option>
              <option value="reseller">Revendedores</option>
            </select>
          </label>
          {beneficiary === 'seller'
            ? <SellerSelect mode="selection" label="Vendedor" options={(sellers.data?.content ?? []).map((seller) => ({
              id: seller.id, name: seller.status === 'INACTIVE' ? `${seller.displayName} (inactivo)` : seller.displayName,
            }))} value={beneficiaryId} onChange={(id) => setFilter('sellerId', id)} loading={sellers.isPending} disabled={sellers.isError || saving} />
            : <CustomerSelect mode="selection" label="Revendedor" options={(resellers.data?.content ?? []).map((reseller) => ({
              id: reseller.id, name: reseller.status === 'INACTIVE' ? `${reseller.name} (inactivo)` : reseller.name,
            }))} value={beneficiaryId} onChange={(id) => setFilter('resellerId', id)} loading={resellers.isPending} disabled={resellers.isError || saving} />}
          <div className="orders-date-filters">
            <OrderDateFilter id="commission-from" label="Desde" value={dateMin} isoValue={from ?? ''} invalid={Boolean(dateError)}
              describedBy={dateError ? 'commission-date-error' : undefined} onChange={(value) => setFilter('dateMin', value)} />
            <OrderDateFilter id="commission-to" label="Hasta" value={dateMax} isoValue={to ?? ''} invalid={Boolean(dateError)}
              describedBy={dateError ? 'commission-date-error' : undefined} onChange={(value) => setFilter('dateMax', value)} />
          </div>
        </div>
        {dateError && <p id="commission-date-error" className="error-text" role="alert">{dateError}</p>}
        {choices.isError && <div className="page-actions">
          <p role="alert" className="error-text">No se pudo cargar {labels.list}.</p>
          <Button variant="secondary" onClick={() => void choices.refetch()}>Reintentar</Button>
        </div>}
      </Panel>
      {beneficiaryId && !dateError && statement.isPending && <Panel>
        <EmptyState state="loading" title="Cargando comisiones" description={`Consultando los movimientos del ${labels.noun}.`} />
      </Panel>}
      {beneficiaryId && !dateError && statement.isError && <Panel>
        <EmptyState state="error" title="No se pudieron cargar las comisiones" description={statement.error.message}
          action={<Button variant="secondary" onClick={() => void statement.refetch()}>Reintentar</Button>} />
      </Panel>}
      {ready && <>
        <Panel title="Saldo pendiente" description={`Incluye todos los movimientos pendientes del ${labels.noun}. Las fechas filtran el historial.`}>
          <p className="m-0 text-[25px] font-bold">{money(ready.pending.amount)}</p>
          <p className="helper-text mb-[12px]">{movementCount(ready.pending.entryCount)} {ready.pending.entryCount === 1 ? 'pendiente' : 'pendientes'}</p>
          <Button disabled={saving || statement.isFetching || Number(ready.pending.amount) <= 0}
            onClick={() => { setError(''); setConfirmation({ name: ready.name, pending: ready.pending, path }) }}>Registrar pago al {labels.noun}</Button>
        </Panel>
        <Panel title="Movimientos del período" description={`Total del período ${money(ready.periodAmount)}`}>
          <DataTable columns={columns} rows={ready.movements.content.map((movement) => ({
            id: movement.id, orderId: movement.orderId, order: movement.orderNumber, customer: movement.customer, product: movement.product,
            date: formatBusinessDateShort(movement.occurredAt), type: movement.returned ? 'Devolución' : 'Entrega',
            amount: money(movement.amount), paidAt: movement.paidAt ?? '',
            payment: movement.paidAt ? `Pagado el ${formatBusinessDateShort(movement.paidAt)}` : 'Pendiente',
          }))} emptyContent={<p className="table-empty-message">No hay movimientos en esta página.</p>} />
          {(ready.movements.totalElements > 0 || page > 0) && <Pagination label="Paginación de comisiones" summary={<>Página {page + 1} · {movementCount(ready.movements.totalElements)}</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= ready.movements.totalPages} />}
        </Panel>
      </>}
    </div>
    {confirmation && <ConfirmationDialog title="Confirmar pago de comisión"
      description={`Confirmá que pagaste ${money(confirmation.pending.amount)} a ${confirmation.name}. Movimientos incluidos: ${movementCount(confirmation.pending.entryCount)}.`}
      confirmLabel="Registrar pago" confirmVariant="primary" pending={saving} error={error}
      onCancel={() => { setConfirmation(undefined); setError('') }} onConfirm={() => void settle()} />}
  </>
}
