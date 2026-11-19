import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { businessTimeZone, displayDate, parseDate, presetRange, rangeError, todayInArgentina } from '../dashboard/dashboardDates'
import { OrderDateFilter } from '../orders/OrderDateFilter'

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
const money = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 })
const date = new Intl.DateTimeFormat('es-AR', { dateStyle: 'short', timeZone: businessTimeZone })
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
  const beneficiary: Beneficiary = params.get('type') === 'reseller' ? 'reseller' : 'seller'
  const labels = beneficiaryLabels[beneficiary]
  const beneficiaryId = params.get(labels.param) ?? ''
  const dateMin = params.get('dateMin') ?? displayDate(month.start)
  const dateMax = params.get('dateMax') ?? displayDate(month.end)
  const from = parseDate(dateMin)
  const to = parseDate(dateMax)
  const dateError = rangeError(from, to, today)
  const path = `/api/commissions/${labels.api}/${encodeURIComponent(beneficiaryId)}`
  const query = new URLSearchParams({ dateMin: from ?? '', dateMax: to ?? '', page: String(page), size: String(pageSize) })
  const sellers = useQuery({ queryKey: [sellersPath], queryFn: () => apiGetAllPages<Seller>(sellersPath), enabled: beneficiary === 'seller' })
  const resellers = useQuery({ queryKey: [resellersPath], queryFn: () => apiGetAllPages<Reseller>(resellersPath), enabled: beneficiary === 'reseller' })
  const choices = beneficiary === 'seller' ? sellers : resellers
  const statement = useQuery({
    queryKey: ['commission-statement', path, query.toString()],
    queryFn: ({ signal }) => apiGet<Statement>(`${path}?${query}`, signal),
    enabled: Boolean(beneficiaryId) && !dateError,
    retry: false,
  })
  const ready = !dateError && beneficiaryId && statement.isSuccess ? statement.data : undefined

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
      setFeedback(`Pago de ${money.format(Number(result.amount))} registrado para ${confirmation.name}.`)
      await client.invalidateQueries({ queryKey: ['commission-statement', confirmation.path] })
    } catch (cause) {
      const changed = cause instanceof ApiError && cause.status === 409
      setError(changed ? 'El saldo pendiente cambió. Revisá el nuevo importe antes de registrar el pago.'
        : cause instanceof Error ? cause.message : 'No se pudo registrar el pago.')
      if (changed) {
        setConfirmation(undefined)
        await client.invalidateQueries({ queryKey: ['commission-statement', confirmation.path] })
      }
    } finally {
      setSaving(false)
    }
  }

  return <>
    <PageHeader eyebrow="Administración" title="Liquidación de comisiones"
      actions={<Button variant="secondary" href="/admin/settings/commissions">Volver a comisiones</Button>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && !confirmation && <p className="error-text" role="alert">{error}</p>}
    <div className="content-grid">
      <Panel title="Beneficiario y período">
        <div className="form-grid mb-0">
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
          <div className="orders-date-filters ml-0">
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
      {beneficiaryId && !dateError && statement.isPending && <Panel><div role="status">
        <EmptyState title="Cargando comisiones" description={`Consultando los movimientos del ${labels.noun}.`} />
      </div></Panel>}
      {beneficiaryId && !dateError && statement.isError && <Panel><div role="alert">
        <EmptyState title="No se pudieron cargar las comisiones" description={statement.error.message}
          action={<Button variant="secondary" onClick={() => void statement.refetch()}>Reintentar</Button>} />
      </div></Panel>}
      {ready && <>
        <Panel title="Saldo pendiente" description={`Incluye todos los movimientos pendientes del ${labels.noun}. Las fechas filtran el historial.`}>
          <p className="m-0 text-[25px] font-bold">{money.format(Number(ready.pending.amount))}</p>
          <p className="helper-text mb-[12px]">{movementCount(ready.pending.entryCount)} {ready.pending.entryCount === 1 ? 'pendiente' : 'pendientes'}</p>
          <Button disabled={saving || statement.isFetching || Number(ready.pending.amount) <= 0}
            onClick={() => { setError(''); setConfirmation({ name: ready.name, pending: ready.pending, path }) }}>Registrar pago al {labels.noun}</Button>
        </Panel>
        <Panel title="Movimientos del período" description={`Total del período ${money.format(Number(ready.periodAmount))}`}>
          <DataTable columns={columns} rows={ready.movements.content.map((movement) => ({
            id: movement.id, orderId: movement.orderId, order: movement.orderNumber, customer: movement.customer, product: movement.product,
            date: date.format(new Date(movement.occurredAt)), type: movement.returned ? 'Devolución' : 'Entrega',
            amount: money.format(Number(movement.amount)), paidAt: movement.paidAt ?? '',
            payment: movement.paidAt ? `Liquidado el ${date.format(new Date(movement.paidAt))}` : 'Pendiente',
          }))} emptyContent={<p className="table-empty-message">No hay movimientos en esta página.</p>} />
          {(ready.movements.totalElements > 0 || page > 0) && <div className="pagination" aria-label="Paginación de comisiones">
            <span>Página {page + 1} · {movementCount(ready.movements.totalElements)}</span>
            <div>
              <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Anterior</Button>
              <Button variant="secondary" disabled={page + 1 >= ready.movements.totalPages} onClick={() => setPage(page + 1)}>Siguiente</Button>
            </div>
          </div>}
        </Panel>
      </>}
    </div>
    {confirmation && <ConfirmationDialog title="Confirmar pago de comisión"
      description={`Confirmá que pagaste ${money.format(Number(confirmation.pending.amount))} a ${confirmation.name}. Movimientos incluidos: ${movementCount(confirmation.pending.entryCount)}.`}
      confirmLabel="Registrar pago" confirmVariant="primary" pending={saving} error={error}
      onCancel={() => { setConfirmation(undefined); setError('') }} onConfirm={() => void settle()} />}
  </>
}
