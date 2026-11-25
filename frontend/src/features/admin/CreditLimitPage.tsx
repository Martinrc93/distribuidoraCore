import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { EmptyState } from '../../shared/components/EmptyState'
import { Panel } from '../../shared/components/Panel'
import { formatBusinessDateTime } from '../../shared/dates'
import { parseDecimalInput } from '../../shared/decimal'
import { creditLimitQuery, useSettingsEditorState } from './SettingsEditorState'
import { SettingsPageHeader } from './SettingsPageHeader'

export default function CreditLimitPage() {
  const query = useQuery(creditLimitQuery)
  const { creditDraft, setCreditDraft, saveCredit } = useSettingsEditorState()
  const [validationError, setValidationError] = useState('')
  const savedLimit = query.data
  const savedAmount = String(savedLimit?.creditLimit ?? '')
  const enabled = creditDraft?.enabled ?? savedLimit?.enabled ?? false
  const amount = creditDraft?.amount ?? savedAmount
  const hasChanges = enabled !== savedLimit?.enabled || (enabled && amount !== savedAmount)
  const error = validationError || (saveCredit.error instanceof ApiError && saveCredit.error.status === 403
    ? 'No tenés permiso para cambiar el límite de crédito.' : saveCredit.error?.message)

  function update(nextEnabled: boolean, nextAmount: string) {
    const changed = nextEnabled !== savedLimit?.enabled || (nextEnabled && nextAmount !== savedAmount)
    setCreditDraft(changed ? { enabled: nextEnabled, amount: nextAmount } : undefined)
    setValidationError('')
    if (!saveCredit.isIdle) saveCredit.reset()
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!hasChanges || saveCredit.isPending) return
    const limit = enabled ? parseDecimalInput(amount) : null
    if (limit !== null && (!amount.trim() || !Number.isFinite(limit) || limit < 0)) {
      setValidationError('Ingresá un límite no negativo o desactivá el control.')
      return
    }
    setValidationError('')
    saveCredit.mutate(limit)
  }

  return <div className="max-w-[680px]">
    <SettingsPageHeader title="Límite de crédito" description="Al confirmar un pedido, el sistema avisa si el saldo proyectado supera este importe. El pedido se puede confirmar igual." />
      <Panel
        title="Regla global"
        description="Se aplica a todo el comercio."
        action={savedLimit && (
          <div role="status"><Badge tone={!hasChanges && savedLimit.enabled ? 'soft' : 'muted'}>
            {hasChanges ? 'Sin guardar' : savedLimit.enabled ? 'Activo' : 'Desactivado'}
          </Badge></div>
        )}
      >
        {query.isPending ? <EmptyState compact state="loading" title="Cargando configuración" description="Consultando el límite de crédito actual." />
          : query.isError && !savedLimit ? <EmptyState compact state="error" title="No se pudo cargar el límite" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
          : savedLimit && <form className="grid gap-[16px]" onSubmit={submit}>
            <div className="grid rounded-[5px] bg-canvas [border:1px_solid_var(--line)]">
              <label className="radio-row min-h-[44px] cursor-pointer gap-[10px] px-[12px] font-bold text-ink">
                <input className="m-0 size-[18px] shrink-0 accent-accent" type="checkbox" checked={enabled} onChange={(event) => update(event.target.checked, amount)} disabled={saveCredit.isPending} />
                Activar límite global de crédito
              </label>
              {enabled && <div className="px-[12px] pb-[12px]">
                <label className="field w-full max-w-[240px]">
                  <span>Límite de crédito</span>
                  <input className="input currency-input" type="text" inputMode="decimal" value={amount} onChange={(event) => update(enabled, event.target.value)} disabled={saveCredit.isPending} required />
                </label>
              </div>}
            </div>
            {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
            {saveCredit.isSuccess && <p className="success-text m-0" role="status">{enabled ? 'Límite de crédito actualizado.' : 'Límite de crédito desactivado.'}</p>}
            <div className="flex flex-wrap items-center justify-between gap-[12px]">
              <Button type="submit" disabled={!hasChanges || saveCredit.isPending}>{saveCredit.isPending ? 'Guardando...' : 'Guardar límite'}</Button>
              {savedLimit.updatedAt && <small className="helper-text m-0">Última actualización el <time dateTime={savedLimit.updatedAt}>{formatBusinessDateTime(savedLimit.updatedAt)}</time></small>}
            </div>
          </form>}
      </Panel>
  </div>
}
