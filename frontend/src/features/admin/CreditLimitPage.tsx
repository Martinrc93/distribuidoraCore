import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { apiGet, apiPut, ApiError } from '../../shared/api/client'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { OperationShortcutsPanel } from './OperationShortcutsPanel'
import { BusinessProfilePanel } from './BusinessProfilePanel'

type CreditLimit = { creditLimit: number | null; enabled: boolean; updatedAt?: string | null; updatedBy?: string | null }
const KEY = ['/api/settings/credit-limit']
const updateFormat = new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' })

export default function CreditLimitPage() {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: KEY, queryFn: () => apiGet<CreditLimit>('/api/settings/credit-limit') })
  const [enabled, setEnabled] = useState(true)
  const [amount, setAmount] = useState('')
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)
  const savedLimit = query.data
  const hasChanges = savedLimit && (
    enabled !== savedLimit.enabled || (enabled && amount !== String(savedLimit.creditLimit ?? ''))
  )

  useEffect(() => {
    if (!savedLimit) return
    setEnabled(savedLimit.enabled)
    setAmount(String(savedLimit.creditLimit ?? ''))
  }, [savedLimit])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setFeedback('')
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
    <PageHeader eyebrow="Administración" title="Configuración comercial" />
    <div className="content-grid two-thirds max-w-[1080px] items-start">
      <div className="col-span-full"><BusinessProfilePanel /></div>
      <Panel
        title="Límite de crédito"
        description="Si el saldo proyectado supera el límite al confirmar un pedido, el sistema registra una advertencia y permite continuar."
        action={savedLimit && (
          <Badge tone={!hasChanges && savedLimit.enabled ? 'soft' : 'muted'}>
            {hasChanges ? 'Sin guardar' : savedLimit.enabled ? 'Activo' : 'Desactivado'}
          </Badge>
        )}
      >
        {query.isLoading ? <EmptyState compact state="loading" title="Cargando configuración" description="Consultando el límite de crédito actual." />
          : query.isError ? <EmptyState compact state="error" title="No se pudo cargar el límite" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
          : <form className="grid gap-[16px]" onSubmit={submit}>
            <div className="grid rounded-[5px] bg-canvas [border:1px_solid_var(--line)]">
              <label className="radio-row min-h-[44px] cursor-pointer gap-[10px] px-[12px] font-bold text-ink">
                <input className="m-0 size-[18px] shrink-0 accent-accent" type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} disabled={saving} />
                Activar límite global de crédito
              </label>
              {enabled && <div className="px-[12px] pb-[12px]">
                <label className="field w-full max-w-[240px]">
                  <span>Límite de crédito</span>
                  <input className="input currency-input" type="text" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} disabled={saving} required />
                </label>
              </div>}
            </div>
            {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
            {feedback && <p className="success-text m-0" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
            <div className="flex flex-wrap items-center justify-between gap-[12px]">
              <Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Guardar límite'}</Button>
              {savedLimit?.updatedAt && <small className="helper-text m-0">Última actualización el <time dateTime={savedLimit.updatedAt}>{updateFormat.format(new Date(savedLimit.updatedAt))}</time></small>}
            </div>
          </form>}
      </Panel>
      <Panel title="Comisiones" description="Administrá los porcentajes de vendedores y revendedores y consultá los saldos pendientes.">
        <div className="quick-actions">
          <Button variant="secondary" href="/admin/settings/commissions" fullWidth>Planes y asignaciones</Button>
          <Button variant="secondary" href="/admin/settings/commissions/settlements?type=reseller" fullWidth>Liquidación de revendedores</Button>
        </div>
      </Panel>
      <div className="col-span-full"><OperationShortcutsPanel /></div>
    </div>
  </>
}
