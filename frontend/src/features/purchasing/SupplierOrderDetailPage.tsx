import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet, apiPost } from '../../shared/api/client'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DataTable } from '../../shared/components/DataTable'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { displayDate } from '../dashboard/dashboardDates'
import { SupplierOrderForm } from './SupplierOrderForm'
import { purchaseStatusLabel, purchaseStatusTone, supplierOrdersKey, type SupplierOrderDetail } from './supplierOrders'

type PendingAction = 'confirm' | 'cancel'

const statusHelp = {
  LEGACY: 'Compra registrada antes de que las compras movieran stock. Editarla o anularla no cambia el stock.',
  DRAFT: 'Borrador: todavía no movió stock. Al confirmarla, las cantidades se suman al stock.',
  CONFIRMED: 'Confirmada: sus cantidades ya se sumaron al stock. Si la editás se ajusta la diferencia; si la anulás, se descuenta.',
  CANCELLED: 'Anulada: no puede modificarse.',
} as const

export default function SupplierOrderDetailPage() {
  const { orderId } = useParams()
  const [params] = useSearchParams()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [action, setAction] = useState<PendingAction>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const query = useQuery({ queryKey: [...supplierOrdersKey, orderId], queryFn: ({ signal }) => apiGet<SupplierOrderDetail>(`/api/supplier-orders/${orderId}`, signal), retry: false })
  const back = `/supplier-orders${params.toString() ? `?${params}` : ''}`
  if (query.isLoading) return <div role="status"><EmptyState title="Cargando compra" description="Consultando la solicitud al proveedor." /></div>
  if (query.isError || !query.data) return <div role="alert"><EmptyState title="No se pudo cargar la compra" description={query.error?.message ?? 'Compra no disponible.'} action={<><Button variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button><Button variant="link" href={back}>Volver a compras</Button></>} /></div>
  const { order, items } = query.data
  const status = order.status ?? 'LEGACY'

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: supplierOrdersKey }),
      queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products') || String(queryKey[0]).startsWith('/api/inventory') }),
    ])
  }

  async function runAction() {
    if (!action || pending) return
    setPending(true)
    setError('')
    try {
      await apiPost(`/api/supplier-orders/${order.id}/${action}`, {})
      await refresh()
      setFeedback(action === 'confirm' ? `Compra ${order.number} confirmada: las cantidades se sumaron al stock.`
        : status === 'CONFIRMED' ? `Compra ${order.number} anulada: se descontaron sus cantidades del stock.` : `Compra ${order.number} anulada.`)
      setAction(undefined)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'No se pudo completar la operación.')
    } finally {
      setPending(false)
    }
  }

  return <>
    <PageHeader eyebrow="Operación" title={order.number} description={`${order.supplier} · ${displayDate(order.date)}`} actions={<Button variant="secondary" href={back}>Volver a compras</Button>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {editing ? <SupplierOrderForm initial={query.data} onClose={() => setEditing(false)} onSaved={(result) => {
      setEditing(false)
      setFeedback(status === 'CONFIRMED' ? `Compra ${result.number} actualizada: el stock se ajustó por la diferencia.` : `Compra ${result.number} actualizada.`)
    }} />
      : <Panel title="Compra" description={statusHelp[status]} action={<Badge tone={purchaseStatusTone[status]}>{purchaseStatusLabel[status]}</Badge>}>
        <div className="page-actions mb-[18px] items-start">
          <div className="flex min-w-0 flex-wrap items-start gap-[8px] [&>.button]:flex-none">
            <DocumentActions key={order.id} path={`/api/supplier-orders/${encodeURIComponent(order.id)}/documents/a4`}
              filename={`compra-${order.number}.pdf`} downloadLabel="Descargar PDF" />
            {status === 'DRAFT' && <Button onClick={() => { setError(''); setAction('confirm') }}>Confirmar compra</Button>}
            {status !== 'CANCELLED' && <Button variant="secondary" onClick={() => { setFeedback(''); setEditing(true) }}>Editar compra</Button>}
          </div>
          {status !== 'CANCELLED' && <div className="ml-auto flex">
            <Button variant="danger" onClick={() => { setError(''); setAction('cancel') }}>Anular compra</Button>
          </div>}
        </div>
        <DataTable columns={[{ key: 'product', label: 'Producto', emphasis: true }, { key: 'quantity', label: 'Cantidad', align: 'right' }]}
          rows={items.map((item) => ({ id: item.productId, product: item.productName, quantity: String(item.quantity) }))} />
      </Panel>}
    {action && <ConfirmationDialog
      title={action === 'confirm' ? `¿Confirmar la compra ${order.number}?` : `¿Anular la compra ${order.number}?`}
      description={action === 'confirm'
        ? 'Las cantidades de todos los productos se van a sumar al stock. Después vas a poder editarla o anularla con movimientos compensatorios.'
        : status === 'CONFIRMED' ? 'Se van a descontar del stock las cantidades de la compra. La compra queda anulada y no se puede modificar.'
          : 'La compra queda anulada y no se puede modificar. No cambia el stock.'}
      confirmLabel={action === 'confirm' ? 'Confirmar compra' : 'Anular compra'}
      confirmVariant={action === 'confirm' ? 'primary' : 'danger'}
      cancelLabel="Volver" pendingLabel={action === 'confirm' ? 'Confirmando...' : 'Anulando...'}
      pending={pending} error={error}
      onCancel={() => setAction(undefined)} onConfirm={() => void runAction()}
    />}
  </>
}
