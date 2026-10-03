import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiPost, ApiError } from '../../shared/api/client'
import type { DeliveryAttemptDraft } from './DeliveryAttemptDialog'
import { deliveryAmount } from './DeliveryAttemptDialog'

type Props = { orderId: string; saleBalance: number; previousDebtAvailable?: number; onSaved: (result: DeliveryAttemptDraft['result']) => void }
const emptyDraft = (): DeliveryAttemptDraft => ({ result: 'DELIVERED', observation: '', payments: [], transferReference: '', includePreviousDebt: false, previousDebtAmount: '' })
const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)

export function useOrderDelivery({ orderId, saleBalance, previousDebtAvailable = 0, onSaved }: Props) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<DeliveryAttemptDraft>(emptyDraft)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)

  function changeDraft(value: DeliveryAttemptDraft) { setDraft(value); setError('') }
  function reset() { setDraft(emptyDraft()); setError('') }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending || !orderId) return
    const { result, observation, payments, transferReference } = draft
    if (result === 'FAILED' && !observation.trim()) { setError('Agregá una observación para un intento fallido.'); return }
    if (result === 'FAILED' && payments.length) { setError('Un intento fallido no puede incluir pagos.'); return }
    const hasTransfer = payments.some(payment => payment.method === 'BANK_TRANSFER')
    if (hasTransfer && !transferReference.trim()) { setError('Ingresa el número de transferencia.'); return }
    if (transferReference.trim() && !hasTransfer) { setError('El número requiere al menos un pago por transferencia.'); return }
    const previousDebtAmount = draft.includePreviousDebt ? deliveryAmount(draft.previousDebtAmount) : 0
    if (draft.includePreviousDebt && (!Number.isFinite(previousDebtAmount) || previousDebtAmount <= 0 || previousDebtAmount > previousDebtAvailable)) { setError(`La deuda anterior debe ser mayor a cero y no superar ${money(previousDebtAvailable)}.`); return }
    const amount = payments.reduce((sum, payment) => sum + deliveryAmount(payment.amount), 0)
    if (payments.some(payment => !Number.isFinite(deliveryAmount(payment.amount)) || deliveryAmount(payment.amount) <= 0)) { setError('Cada pago debe ser mayor a cero y tener hasta 4 decimales.'); return }
    if (Math.round(amount * 10000) > Math.round((saleBalance + previousDebtAmount) * 10000)) { setError(`El pago supera el saldo pendiente de ${money(saleBalance + previousDebtAmount)}.`); return }
    setPending(true)
    setError('')
    try {
      await apiPost(`/api/orders/${orderId}/delivery-attempts`, {
        result, observation: result === 'FAILED' ? observation.trim() || null : null,
        ...(payments.length ? { payments: payments.map(payment => ({ method: payment.method, amount: deliveryAmount(payment.amount) })) } : {}),
        ...(transferReference.trim() ? { transferReference: transferReference.trim() } : {}),
        ...(previousDebtAmount > 0 ? { previousDebtAmount } : {}),
      })
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => {
        const key = String(queryKey[0])
        return ['/api/orders', '/api/sales', '/api/payments', '/api/customers', '/api/dashboard'].some(prefix => key.startsWith(prefix))
          || ['sale-detail', 'customer-debts', 'dashboard'].includes(key)
      } })
      onSaved(result)
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        await queryClient.invalidateQueries({ queryKey: [`/api/orders/${orderId}`] })
      }
      setError(cause instanceof ApiError && cause.status === 403 ? 'Tu usuario no tiene permiso para realizar esta acción.'
        : cause instanceof ApiError && cause.status === 404 ? 'El pedido ya no está disponible.'
          : cause instanceof Error ? cause.message : 'No se pudo registrar el intento de entrega.')
    } finally { setPending(false) }
  }

  return { draft, changeDraft, error, clearError: () => setError(''), pending, submit, reset }
}
