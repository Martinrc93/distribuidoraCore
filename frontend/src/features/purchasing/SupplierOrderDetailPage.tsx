import { useQuery } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { displayDate } from '../dashboard/dashboardDates'
import { money, supplierOrdersKey, type SupplierOrderDetail } from './supplierOrders'

export default function SupplierOrderDetailPage() {
  const { orderId } = useParams()
  const [params] = useSearchParams()
  const query = useQuery({ queryKey: [...supplierOrdersKey, orderId], queryFn: ({ signal }) => apiGet<SupplierOrderDetail>(`/api/supplier-orders/${orderId}`, signal), retry: false })
  const back = `/supplier-orders${params.toString() ? `?${params}` : ''}`
  if (query.isLoading) return <div role="status"><EmptyState title="Cargando pedido" description="Consultando la solicitud al proveedor." /></div>
  if (query.isError || !query.data) return <div role="alert"><EmptyState title="No se pudo cargar el pedido" description={query.error?.message ?? 'Pedido no disponible.'} action={<><Button variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button><Button variant="link" href={back}>Volver a pedidos a proveedores</Button></>} /></div>
  const { order, items } = query.data
  return <>
    <PageHeader eyebrow="Operación" title={order.number} description={`${order.supplier} · ${displayDate(order.date)}`} actions={<Button variant="secondary" href={back}>Volver a pedidos a proveedores</Button>} />
    <Panel title="Solicitud registrada" description={`Total estimado: ${money(order.total)}`}>
      <DataTable columns={[{ key: 'product', label: 'Producto', emphasis: true }, { key: 'quantity', label: 'Cantidad', align: 'right' }, { key: 'cost', label: 'Costo unitario', align: 'right' }, { key: 'total', label: 'Subtotal', align: 'right' }]}
        rows={items.map((item) => ({ id: item.productId, product: item.productName, quantity: String(item.quantity), cost: money(item.unitCost), total: money(item.lineTotal) }))} />
    </Panel>
  </>
}
