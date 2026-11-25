import { invalidateDomains } from '../../shared/api/queryKeys'
import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { apiGet, apiPost, ApiError } from '../../shared/api/client'
import { DeliveryAttemptDialog, deliveryAmount, type DeliveryAttemptDraft } from '../orders/DeliveryAttemptDialog'
import type { Customer } from './customerTypes'

export function AccountSettlementDialog({ customer, onClose, onSaved }: {
  customer: Customer
  onClose: () => void
  /** Receives the remaining balance: zero after a full settlement, positive after a partial payment. */
  onSaved: (remainingBalance: number) => void
}) {
  const queryClient = useQueryClient()
  const submitting = useRef(false)
  const [balance, setBalance] = useState(Number(customer.balance))
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [draft, setDraft] = useState<DeliveryAttemptDraft>({
    result: 'DELIVERED', observation: '', payments: [{ method: 'CASH', amount: String(balance).replace('.', ',') }],
    transferReference: '', includePreviousDebt: false, previousDebtAmount: '',
  })

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    const payments = draft.payments.map(payment => ({ method: payment.method, amount: deliveryAmount(payment.amount) }))
    if (!payments.length || payments.some(payment => !Number.isFinite(payment.amount) || payment.amount <= 0)) {
      setError('Cada pago debe ser mayor a cero y tener hasta 4 decimales.'); return
    }
    const total = payments.reduce((sum, payment) => sum + Math.round(payment.amount * 10000), 0)
    const balanceUnits = Math.round(balance * 10000)
    if (balance <= 0 || total > balanceUnits) {
      setError('Los pagos no pueden superar el saldo de la cuenta corriente.'); return
    }
    const partial = total < balanceUnits
    const hasTransfer = payments.some(payment => payment.method === 'BANK_TRANSFER')
    if (hasTransfer && !draft.transferReference.trim()) {
      setError('Ingresa el número de transferencia.'); return
    }
    submitting.current = true
    setPending(true)
    setError('')
    try {
      await apiPost(`/api/customers/${customer.id}/account-payments/settlement`, {
        payments: payments.map(payment => ({ ...payment,
          ...(payment.method === 'BANK_TRANSFER' ? { transferReference: draft.transferReference.trim() } : {}),
        })),
        ...(partial ? { partial: true } : {}),
      })
      await invalidateDomains(queryClient, 'customers', 'sales', 'orders', 'reports')
      onSaved((balanceUnits - total) / 10000)
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        try {
          const current = await apiGet<Customer>(`/api/customers/${customer.id}/account-balance`)
          setBalance(Number(current.balance ?? 0))
        } catch { /* Keep the draft available if the balance refresh fails. */ }
      }
      setError(cause instanceof ApiError && cause.status === 403 ? 'No tienes permiso para registrar pagos de cuenta corriente.'
        : cause instanceof ApiError && cause.status === 404 ? 'El cliente ya no está disponible.'
          : cause instanceof Error ? cause.message : 'No se pudo registrar el pago.')
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  return <DeliveryAttemptDialog mode="account" orderNumber="" customerName={customer.name} saleBalance={balance}
    value={draft} pending={pending} error={error} onChange={value => { setDraft(value); setError('') }}
    onSubmit={submit} onClose={onClose} returnFocusId={`settle-account-${customer.id}`} fallbackFocusId="customers-search" />
}
