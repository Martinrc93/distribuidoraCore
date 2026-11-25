import { invalidateDomains } from '../../shared/api/queryKeys'
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiPost } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'

export type OrderOperations = { id: string; number: string; status: string; fulfillmentStatus?: string; reviewPending?: boolean }

export function OrderReviewBadge({ pending }: { pending?: boolean }) {
  return pending ? <span className="order-review-pending"><Badge>Vendedor · Pendiente de revisión</Badge></span> : null
}

/**
 * Shared by the list, detail and administrator's daily review panel.
 * `only` renders a single action, so the list can show preparation as the row's next step and keep the review in its menu.
 */
export function OrderOperationalActions({ order, onSaved, only, prepareVariant = 'secondary' }: { order: OrderOperations; onSaved?: (message: string) => void; only?: 'prepare' | 'review'; prepareVariant?: 'primary' | 'secondary' }) {
  const client = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canPrepare = isAdmin || hasAuthority('ORDER_PREPARE')
  const [action, setAction] = useState<'prepare' | 'review'>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')

  async function confirm() {
    if (pending || !action) return
    setPending(true)
    setError('')
    try {
      await apiPost(`/api/orders/${order.id}/${action}`, {})
      await invalidateDomains(client, 'orders', 'stock', 'reports')
      const message = action === 'prepare' ? `Pedido ${order.number} preparado.` : `Revisión del pedido ${order.number} confirmada.`
      setAction(undefined)
      setFeedback(message)
      onSaved?.(message)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'No se pudo guardar. Volvé a intentar.')
    } finally { setPending(false) }
  }

  return <>
    {only !== 'review' && canPrepare && order.status === 'CONFIRMED' && order.fulfillmentStatus === 'PENDING' && <Button type="button" variant={prepareVariant} aria-label={`Preparar pedido ${order.number}`} onClick={() => { setError(''); setAction('prepare') }}>Preparar</Button>}
    {only !== 'prepare' && isAdmin && order.reviewPending && <Button type="button" variant="secondary" aria-label={`Confirmar revisión del pedido ${order.number}`} onClick={() => { setError(''); setAction('review') }}>Confirmar revisión</Button>}
    {feedback && !onSaved && <span className="success-text" role="status">{feedback}</span>}
    {action && <ConfirmationDialog title={`${action === 'prepare' ? 'Preparar' : 'Confirmar revisión del'} pedido ${order.number}`} description={action === 'prepare'
      ? 'Se descontarán sus productos del stock y quedará listo para entregar. La revisión administrativa es independiente.'
      : 'Se registrará tu revisión y se quitará el distintivo de pendiente. Los productos, el stock y los cobros conservan su estado.'}
      confirmLabel={action === 'prepare' ? 'Confirmar preparación' : 'Confirmar revisión'} confirmVariant="primary" pending={pending} error={error} onCancel={() => setAction(undefined)} onConfirm={() => void confirm()} />}
  </>
}
