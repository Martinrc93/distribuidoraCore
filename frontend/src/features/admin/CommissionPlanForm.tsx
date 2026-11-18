import { useQuery } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { apiGet, apiPost, apiPut, ApiError } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { Button } from '../../shared/components/Button'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { SearchableSelect } from '../../shared/components/SearchableSelect'
import { useDiscardChanges } from '../../shared/useDiscardChanges'

export const commissionPlansPath = '/api/settings/commission-plans'
type Scope = 'DEFAULT' | 'CATEGORY' | 'BRAND' | 'PRODUCT'
type Kind = 'FIXED' | 'PERCENT'
type Rule = { scope: Scope; targetId: string | null; kind: Kind; value: string }
export type CommissionPlan = { id: string; name: string; version: number; rules: Rule[] }
type RuleDraft = Rule & { key: number }
type Option = { id: string; name: string; status?: string }

const scopes: Record<Scope, string> = { DEFAULT: 'Regla general', CATEGORY: 'Categoría', BRAND: 'Marca', PRODUCT: 'Artículo' }
const emptyRule = (key: number, scope: Scope): RuleDraft => ({ key, scope, targetId: null, kind: 'PERCENT', value: '' })
const productsPath = '/api/products?page=0&size=100&includeStock=false'

function RuleTarget({ rule, busy, onChange }: { rule: RuleDraft; busy: boolean; onChange: (id: string) => void }) {
  const path = rule.scope === 'BRAND' ? '/api/brands' : rule.scope === 'CATEGORY' ? '/api/categories' : productsPath
  const options = useQuery({
    queryKey: ['commission-rule-options', path],
    queryFn: async () => path === productsPath ? (await apiGetAllPages<Option>(path)).content : apiGet<Option[]>(path),
  })

  return <div className="min-w-0">
    <SearchableSelect label={scopes[rule.scope]} options={(options.data ?? []).map((option) => ({
      id: option.id, name: option.status === 'INACTIVE' ? `${option.name} (inactivo)` : option.name,
    }))} value={rule.targetId ?? ''} onChange={onChange} allLabel="Seleccionar..."
      unavailableLabel="Opción no disponible" loadingLabel="Cargando opciones…" fullWidth required
      loading={options.isPending} disabled={busy || options.isError} />
    {options.isError && <div className="page-actions mt-[8px]">
      <span role="alert" className="error-text">No se pudieron cargar las opciones.</span>
      <Button type="button" variant="secondary" onClick={() => void options.refetch()}>Reintentar</Button>
    </div>}
  </div>
}

export function CommissionPlanForm({ plan, onClose, onSaved }: { plan?: CommissionPlan; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(plan?.name ?? '')
  const [rules, setRules] = useState<RuleDraft[]>(() => plan
    ? plan.rules.map((rule, key) => ({ ...rule, key, value: String(rule.value).replace('.', ',') }))
    : [emptyRule(0, 'DEFAULT')])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [dirty, setDirty] = useState(false)
  const sequence = useRef(rules.length)
  const title = plan ? 'Editar plan de comisión' : 'Nuevo plan de comisión'
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: dirty, disabled: busy, onDiscard: onClose })

  function changeRule(key: number, change: Partial<RuleDraft>) {
    setDirty(true)
    setRules((current) => current.map((rule) => rule.key === key ? { ...rule, ...change } : rule))
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const parsed: Rule[] = []
    const targets = new Set<string>()
    for (const [index, rule] of rules.entries()) {
      const normalized = rule.value.trim().replace(',', '.')
      const number = Number(normalized)
      const target = `${rule.scope}:${rule.targetId ?? ''}`
      if ((rule.scope !== 'DEFAULT' && !rule.targetId) || !/^\d{1,15}(\.\d{1,4})?$/.test(normalized) || rule.kind === 'PERCENT' && number > 100) {
        setError(`Revisá la regla ${index + 1}. Ingresá un valor de hasta 4 decimales y un alcance válido.`)
        return
      }
      if (targets.has(target)) {
        setError(`La regla ${index + 1} repite un alcance de este plan.`)
        return
      }
      targets.add(target)
      parsed.push({ scope: rule.scope, targetId: rule.targetId, kind: rule.kind, value: normalized })
    }
    if (!name.trim() || parsed.length === 0) {
      setError('Ingresá un nombre y al menos una regla.')
      return
    }
    setError('')
    setBusy(true)
    try {
      if (plan) await apiPut(`${commissionPlansPath}/${plan.id}`, { name, version: plan.version, rules: parsed })
      else await apiPost(commissionPlansPath, { name, rules: parsed })
      onSaved()
    } catch (cause) {
      setError(cause instanceof ApiError && cause.status === 409
        ? 'El nombre ya está en uso o el plan cambió mientras lo editabas. Revisá el nombre o volvé a abrir el plan.'
        : cause instanceof Error ? cause.message : 'No se pudo guardar el plan.')
    } finally {
      setBusy(false)
    }
  }

  return <>
    <FormModal label={title} onClose={requestDiscard} busy={busy}>
      <Panel title={title}>
        <form className="grid gap-[14px]" onSubmit={save}>
          <label className="field"><span>Nombre del plan</span>
            <input className="input" value={name} required maxLength={100} disabled={busy}
              onChange={(event) => { setDirty(true); setName(event.target.value) }} />
          </label>
          <p className="helper-text m-0">La regla general es opcional. Agregá una regla por categoría, marca o artículo para definir un valor distinto.</p>
          {rules.map((rule, index) => <fieldset key={rule.key} className="m-0 min-w-0 rounded-[5px] border border-line p-[12px]" disabled={busy}>
            <legend className="px-[4px] text-[13px] font-bold">Regla {index + 1}</legend>
            <div className="form-grid">
              <label className="field"><span>Alcance</span>
                <select className="select min-w-0 w-full" value={rule.scope}
                  onChange={(event) => changeRule(rule.key, { scope: event.target.value as Scope, targetId: null })}>
                  {Object.entries(scopes).map(([scope, label]) => <option key={scope} value={scope}>{label}</option>)}
                </select>
              </label>
              {rule.scope !== 'DEFAULT' && <RuleTarget rule={rule} busy={busy} onChange={(id) => changeRule(rule.key, { targetId: id || null })} />}
              <label className="field"><span>Tipo de comisión</span>
                <select className="select min-w-0 w-full" value={rule.kind}
                  onChange={(event) => changeRule(rule.key, { kind: event.target.value as Kind })}>
                  <option value="PERCENT">Porcentual</option><option value="FIXED">Fija</option>
                </select>
              </label>
              <label className="field"><span>{rule.kind === 'PERCENT' ? 'Porcentaje (%)' : 'Importe fijo ($)'}</span>
                <input className={`input${rule.kind === 'FIXED' ? ' currency-input' : ''}`} value={rule.value}
                  inputMode="decimal" required onChange={(event) => changeRule(rule.key, { value: event.target.value })} />
              </label>
            </div>
            <div className="page-actions mt-[8px]">
              <Button type="button" variant="secondary" aria-label={`Quitar regla ${index + 1}`}
                onClick={() => { setDirty(true); setRules((current) => current.filter((item) => item.key !== rule.key)) }}>Quitar regla</Button>
            </div>
          </fieldset>)}
          <div className="page-actions">
            <Button type="button" variant="secondary" disabled={busy}
              onClick={() => { setDirty(true); const rule = emptyRule(sequence.current++, 'PRODUCT'); setRules((current) => [...current, rule]) }}>Agregar regla</Button>
          </div>
          {error && <p role="alert" className="error-text m-0">{error}</p>}
          <div className="page-actions">
            <Button type="button" variant="secondary" onClick={requestDiscard} disabled={busy}>Cancelar</Button>
            <Button type="submit" disabled={busy}>{busy ? 'Guardando...' : 'Guardar plan'}</Button>
          </div>
        </form>
      </Panel>
    </FormModal>
    {discardDialog}
  </>
}
