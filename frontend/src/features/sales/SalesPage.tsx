import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'

type Sale = { id: string; number: string; customer: string; total: number; paid: number; balance: number; status: string; date: string }
type SaleItem = { saleItemId: string; productId: string; productName: string; quantity: number; returnedQuantity: number; returnableQuantity: number }
type SaleDetail = { sale: { id: string; number: string; status: string }; saleItems: SaleItem[] }
type Row = Record<string, string>
const money = (value: unknown) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
const formatDate = (value: string) => new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium' }).format(new Date(value))
const stateName: Record<string, string> = { CONFIRMED: 'Confirmada', DELIVERED: 'Entregada', CANCELLED: 'Cancelada', RETURNED: 'Con devoluciones' }

export default function SalesPage() {
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const [selectedSaleId, setSelectedSaleId] = useState('')
  const [returnReason, setReturnReason] = useState('')
  const [returnQuantities, setReturnQuantities] = useState<Record<string, string>>({})
  const [returnError, setReturnError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [savingReturn, setSavingReturn] = useState(false)
  const path = `/api/sales?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}`
  const query = useQuery({ queryKey: [path], queryFn: () => apiGet<ApiPage<Sale>>(path) })
  const detailQuery = useQuery({
    queryKey: ['sale-detail', selectedSaleId],
    queryFn: () => apiGet<SaleDetail>(`/api/sales/${selectedSaleId}`),
    enabled: Boolean(selectedSaleId),
  })

  async function submitReturn(event: FormEvent) {
    event.preventDefault()
    if (!isAdmin || savingReturn || !selectedSaleId || !detailQuery.data) return
    const items = detailQuery.data.saleItems.flatMap((item) => {
      const quantity = Number(returnQuantities[item.saleItemId] ?? 0)
      return quantity > 0 ? [{ saleItemId: item.saleItemId, quantity }] : []
    })
    if (!returnReason.trim()) { setReturnError('Ingresá el motivo de la devolución.'); return }
    if (items.length === 0) { setReturnError('Ingresá una cantidad para al menos una línea.'); return }
    if (items.some((line) => {
      const source = detailQuery.data?.saleItems.find((item) => item.saleItemId === line.saleItemId)
      return !source || line.quantity > source.returnableQuantity || Math.round(line.quantity * 2) !== line.quantity * 2
    })) { setReturnError('Cada cantidad debe ser múltiplo de 0,5 y no superar lo disponible.'); return }
    setSavingReturn(true)
    setReturnError('')
    setFeedback('')
    try {
      await apiPost(`/api/sales/${selectedSaleId}/returns`, { reason: returnReason.trim(), items })
      await Promise.all([
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/sales?') }),
        queryClient.invalidateQueries({ queryKey: ['sale-detail', selectedSaleId] }),
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/inventory') }),
      ])
      setReturnReason('')
      setReturnQuantities({})
      setFeedback('Devolución registrada y stock actualizado. Esta operación no genera un reintegro ni crédito monetario.')
    } catch (cause) {
      const status = cause instanceof ApiError ? cause.status : undefined
      setReturnError(status === 403 ? 'No tenés permiso para registrar devoluciones.' : status === 409 ? cause instanceof Error ? cause.message : 'La cantidad disponible cambió.' : cause instanceof Error ? cause.message : 'No se pudo registrar la devolución.')
    } finally {
      setSavingReturn(false)
    }
  }
  const rows: Row[] = (query.data?.content ?? []).map((sale) => ({
    id: sale.id,
    number: sale.number,
    customer: sale.customer,
    date: formatDate(sale.date),
    total: money(sale.total),
    paid: money(sale.paid),
    balance: money(sale.balance),
    status: sale.status,
  }))
  const columns: TableColumn[] = [
    { key: 'number', label: 'Venta', emphasis: true },
    { key: 'customer', label: 'Cliente' },
    { key: 'date', label: 'Fecha' },
    { key: 'total', label: 'Total', align: 'right' },
    { key: 'paid', label: 'Cobrado', align: 'right' },
    { key: 'balance', label: 'Saldo', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : value === 'CANCELLED' ? 'muted' : 'soft'}>{stateName[value] ?? value}</Badge> },
    { key: 'actions', label: 'Detalle', render: (_value, row) => <Button variant="link" onClick={() => { setSelectedSaleId(row.id); setReturnError(''); setFeedback('') }}>Ver venta</Button> },
  ]

  return <>
    <PageHeader eyebrow="Operación" title="Ventas" description="Consultá importes cobrados, saldos y estado de las ventas." />
    <Panel>
      <div className="toolbar"><label className="field"><span>Buscar ventas</span><input className="input search-input" placeholder="Cliente o número de venta" aria-label="Buscar ventas" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label></div>
      {query.isLoading ? <EmptyState title="Cargando ventas" description="Consultando ventas registradas." /> : query.isError ? <EmptyState title="No se pudieron cargar las ventas" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay ventas para mostrar" description="Probá otra búsqueda." /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} ventas</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {selectedSaleId && <Panel title={`Venta ${detailQuery.data?.sale.number ?? ''}`} description="Líneas reales de la venta y cantidades disponibles para devolver.">
      {detailQuery.isLoading ? <EmptyState title="Cargando venta" description="Consultando snapshots y líneas de venta." /> : detailQuery.isError ? <EmptyState title="No se pudo cargar la venta" description={detailQuery.error.message} action={<Button variant="secondary" onClick={() => setSelectedSaleId('')}>Cerrar</Button>} /> : !detailQuery.data ? <EmptyState title="Venta no disponible" description="No se encontró el detalle de esta venta." /> : <>
        {feedback && <p className="success-text" role="status">{feedback}</p>}
        {returnError && <p className="error-text" role="alert">{returnError}</p>}
        <DataTable columns={[
          { key: 'product', label: 'Producto', emphasis: true },
          { key: 'sold', label: 'Vendida', align: 'right' },
          { key: 'returned', label: 'Devuelta', align: 'right' },
          { key: 'available', label: 'Disponible', align: 'right' },
        ]} rows={detailQuery.data.saleItems.map((item) => ({ id: item.saleItemId, product: item.productName, sold: String(item.quantity), returned: String(item.returnedQuantity), available: String(item.returnableQuantity) }))} />
        {isAdmin && detailQuery.data.sale.status === 'DELIVERED' && <form className="form-grid" onSubmit={submitReturn}>
          <label className="field"><span>Motivo de la devolución</span><input className="input" value={returnReason} onChange={(event) => setReturnReason(event.target.value)} maxLength={500} required disabled={savingReturn} /></label>
          {detailQuery.data.saleItems.filter((item) => item.returnableQuantity > 0).map((item) => <label className="field" key={item.saleItemId}><span>Cantidad a devolver · {item.productName} (máx. {item.returnableQuantity})</span><input className="input" type="text" inputMode="decimal" min="0" max={item.returnableQuantity} step="0.5" aria-label={`Cantidad a devolver · ${item.productName}`} value={returnQuantities[item.saleItemId] ?? ''} onChange={(event) => setReturnQuantities((current) => ({ ...current, [item.saleItemId]: event.target.value }))} disabled={savingReturn} /></label>)}
          <p className="helper-text">La devolución reincorpora unidades al stock del depósito original. El comando actual no realiza reintegros ni acredita la cuenta corriente.</p>
          <Button type="submit" disabled={savingReturn}>{savingReturn ? 'Registrando…' : 'Registrar devolución'}</Button>
        </form>}
        <Button variant="secondary" onClick={() => setSelectedSaleId('')}>Cerrar detalle</Button>
      </>}
    </Panel>}
  </>
}
