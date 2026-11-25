import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../shared/api/queryKeys'
import { useEffect, useState } from 'react'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'
import { DataTable } from '../../shared/components/DataTable'
import { OrderOperationalActions, OrderReviewBadge, type OrderOperations } from '../orders/OrderOperationalActions'

type PendingOrder = OrderOperations & { customer: string; seller: string }
export const pendingReviewsPath = '/api/orders?page=0&size=5&reviewPending=true'

/** `hideWhenEmpty` leaves the count to «Para atender ahora» while nothing is pending. */
export function PendingOrderReviews({ hideWhenEmpty = false }: { hideWhenEmpty?: boolean }) {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const [feedback, setFeedback] = useState('')
  useEffect(() => { if (feedback) document.getElementById('pending-reviews-link')?.focus() }, [feedback])
  const query = useQuery({ queryKey: queryKeys.dashboard.orderReviews, queryFn: ({ signal }) => apiGet<ApiPage<PendingOrder>>(pendingReviewsPath, signal), enabled: isAdmin, retry: false })
  if (!isAdmin) return null
  if (hideWhenEmpty && !feedback && query.data?.totalElements === 0) return null
  return <Panel title={`Pedidos de vendedores por revisar${query.data ? ` (${query.data.totalElements})` : ''}`} description="Revisión administrativa al cierre del día. Incluye pendientes de todas las fechas; los vendedores pueden seguir operando." action={<Button id="pending-reviews-link" variant="link" href="/orders?review=PENDING&dateMin=&dateMax=">Ver todos los pendientes</Button>}>
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {query.isLoading ? <p role="status">Consultando pedidos pendientes de revisión...</p> : query.isError ? <div role="alert"><p className="error-text">No se pudieron cargar los pedidos por revisar.</p><Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar revisión</Button></div> : <DataTable columns={[
      { key: 'number', label: 'Pedido', emphasis: true }, { key: 'customer', label: 'Cliente' }, { key: 'seller', label: 'Vendedor' },
      { key: 'review', label: 'Revisión', render: () => <OrderReviewBadge pending /> },
      { key: 'action', label: 'Acciones', render: (_value, row) => <div className="table-row-actions"><Button href={`/orders/${row.id}`} variant="link" aria-label={`Abrir pedido ${row.number}`}>Ver detalle</Button><OrderOperationalActions order={{ id: row.id, number: row.number, status: row.status, reviewPending: true }} onSaved={setFeedback} /></div> },
    ]} rows={(query.data?.content ?? []).map(order => ({ id: order.id, number: order.number, customer: order.customer, seller: order.seller, status: order.status }))} emptyContent={<p className="table-empty-message">No hay pedidos pendientes de revisión.</p>} />}
  </Panel>
}
