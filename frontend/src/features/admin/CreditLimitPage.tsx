import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { apiGet, apiPut, ApiError } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type CreditLimit = { creditLimit: number | null; enabled: boolean; updatedAt?: string | null; updatedBy?: string | null }
const KEY = ['/api/settings/credit-limit']
const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 }).format(value)

export default function CreditLimitPage() {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: KEY, queryFn: () => apiGet<CreditLimit>('/api/settings/credit-limit') })
  const [enabled, setEnabled] = useState(true)
  const [amount, setAmount] = useState('')
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!query.data) return
    setEnabled(query.data.enabled)
    setAmount(query.data.creditLimit === null ? '' : String(query.data.creditLimit))
  }, [query.data])

  async function submit(event: FormEvent) {
    event.preventDefault()
    const limit = enabled ? Number(amount) : null
    if (enabled && (!amount.trim() || !Number.isFinite(limit) || Number(limit) < 0)) {
      setError('Ingresá un límite no negativo o desactivá el control.')
      return
    }
    setSaving(true)
    setError('')
    try {
      await apiPut('/api/settings/credit-limit', { creditLimit: limit })
      await queryClient.invalidateQueries({ queryKey: KEY })
      setFeedback(enabled ? 'Límite de crédito actualizado.' : 'Límite de crédito desactivado.')
    } catch (cause) {
      setError(cause instanceof ApiError && cause.status === 403 ? 'No tenés permiso para cambiar el límite de crédito.' : cause instanceof Error ? cause.message : 'No se pudo guardar la configuración.')
    } finally {
      setSaving(false)
    }
  }

  return <>
    <PageHeader eyebrow="Administración" title="Límite de crédito" description="El límite advierte al confirmar pedidos; no bloquea la operación." />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    <Panel title="Configuración comercial">
      {query.isLoading ? <EmptyState title="Cargando configuración" description="Consultando el límite de crédito actual." /> : query.isError ? <EmptyState title="No se pudo cargar la configuración" description={query.error.message} /> : <form className="form-grid" onSubmit={submit}>
        <label className="radio-row"><input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} disabled={saving} /> Activar límite global de crédito</label>
        {enabled && <label className="field"><span>Límite de crédito</span><input className="input" aria-label="Límite de crédito" type="text" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} disabled={saving} required /></label>}
        <p className="helper-text">Si el saldo proyectado supera {enabled ? money(Number(amount || 0)) : 'el límite configurado'}, la confirmación muestra una advertencia auditada, pero continúa.</p>
        {query.data?.updatedAt && <small className="helper-text">Última actualización: {new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(query.data.updatedAt))}</small>}
        <Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Guardar límite'}</Button>
      </form>}
    </Panel>
  </>
}
