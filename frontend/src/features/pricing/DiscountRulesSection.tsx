import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { Panel } from '../../shared/components/Panel'

type RuleKind = 'LINE' | 'ORDER'
type RuleStatus = 'ACTIVE' | 'INACTIVE'
type DiscountRule = {
  id: string
  code: string
  description: string
  kind: RuleKind
  percent: number
  customerId: string | null
  customerName: string | null
  priceListId: string | null
  priceListCode: string | null
  productId: string | null
  sku: string | null
  productName: string | null
  priority: number
  status: RuleStatus
  validFrom: string
  validUntil: string | null
}
type Customer = { id: string; name: string; status: string }
type PriceList = { id: string; code: string; name: string; status: string }
type Product = { id: string; sku: string; name: string; status: string }
type RuleDraft = {
  code: string
  description: string
  kind: RuleKind
  percent: string
  customerId: string
  priceListId: string
  productId: string
  validFrom: string
  validUntil: string
  priority: string
}
type RulePayload = {
  code: string
  description: string
  kind: RuleKind
  percent: number
  customerId: string | null
  priceListId: string | null
  productId: string | null
  validFrom: string | null
  validUntil: string | null
  priority: number
}
type Row = Record<string, string>

const RULES_PATH = '/api/pricing/discount-rules'
const CUSTOMER_PATH = '/api/customers?page=0&size=100'
const LIST_PATH = '/api/pricing/lists?page=0&size=100'
const PRODUCT_PATH = '/api/products?page=0&size=100'
const emptyDraft: RuleDraft = {
  code: '', description: '', kind: 'LINE', percent: '', customerId: '', priceListId: '',
  productId: '', validFrom: '', validUntil: '', priority: '0',
}

function errorMessage(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 403) return 'No tenés permiso para administrar reglas de descuento.'
  return cause instanceof Error ? cause.message : 'No se pudo completar la operación.'
}

function statusLabel(status: RuleStatus) {
  return status === 'ACTIVE' ? 'Activa' : 'Inactiva'
}

export default function DiscountRulesSection() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const [rulesPage, setRulesPage] = useState(0)
  const rulesPath = `${RULES_PATH}?page=${rulesPage}&size=20`
  const rulesKey = [rulesPath]
  const rulesQuery = useQuery({ queryKey: rulesKey, queryFn: () => apiGet<ApiPage<DiscountRule>>(rulesPath) })
  const customersQuery = useQuery({ queryKey: [CUSTOMER_PATH], queryFn: () => apiGet<ApiPage<Customer>>(CUSTOMER_PATH) })
  const listsQuery = useQuery({ queryKey: [LIST_PATH], queryFn: () => apiGet<ApiPage<PriceList>>(LIST_PATH) })
  const productsQuery = useQuery({ queryKey: [PRODUCT_PATH], queryFn: () => apiGet<ApiPage<Product>>(PRODUCT_PATH) })
  const [draft, setDraft] = useState<RuleDraft>(emptyDraft)
  const [editingRule, setEditingRule] = useState<DiscountRule | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [statusRule, setStatusRule] = useState<DiscountRule | undefined>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')

  const customers = (customersQuery.data?.content ?? []).filter((customer) => customer.status === 'ACTIVE')
  const lists = (listsQuery.data?.content ?? []).filter((list) => list.status === 'ACTIVE')
  const products = (productsQuery.data?.content ?? []).filter((product) => product.status === 'ACTIVE')

  function resetForm() {
    setDraft(emptyDraft)
    setEditingRule(undefined)
    setShowForm(false)
  }

  const saveMutation = useMutation({
    mutationFn: async (payload: RulePayload) => editingRule
      ? apiPut(`/api/pricing/discount-rules/${editingRule.id}`, payload)
      : apiPost('/api/pricing/discount-rules', payload),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ predicate: (query) => typeof query.queryKey[0] === 'string' && query.queryKey[0].startsWith(`${RULES_PATH}?page=`) })
      setError('')
      setFeedback(editingRule ? 'Regla de descuento actualizada.' : 'Regla de descuento creada.')
      resetForm()
    },
    onError: (cause) => setError(errorMessage(cause)),
  })

  const statusMutation = useMutation({
    mutationFn: (rule: DiscountRule) => apiPatch(`/api/pricing/discount-rules/${rule.id}/status`, {
      status: rule.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ predicate: (query) => typeof query.queryKey[0] === 'string' && query.queryKey[0].startsWith(`${RULES_PATH}?page=`) })
      setFeedback(`Estado de la regla ${statusRule?.code ?? ''} actualizado.`)
      setStatusRule(undefined)
    },
    onError: (cause) => setError(errorMessage(cause)),
  })

  function startEdit(rule: DiscountRule) {
    setEditingRule(rule)
    setShowForm(true)
    setError('')
    setDraft({
      code: rule.code,
      description: rule.description,
      kind: rule.kind,
      percent: String(rule.percent),
      customerId: rule.customerId ?? '',
      priceListId: rule.priceListId ?? '',
      productId: rule.productId ?? '',
      validFrom: rule.validFrom,
      validUntil: rule.validUntil ?? '',
      priority: String(rule.priority),
    })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    const code = draft.code.trim().toUpperCase()
    const description = draft.description.trim()
    const percent = Number(draft.percent)
    const priority = draft.priority.trim() ? Number(draft.priority) : 0
    if (!/^[A-Z0-9_-]{2,40}$/.test(code)) { setError('El código debe tener entre 2 y 40 caracteres alfanuméricos, guion o guion bajo.'); return }
    if (!description || description.length > 160) { setError('Ingresá una descripción de hasta 160 caracteres.'); return }
    if (!Number.isFinite(percent) || percent <= 0 || percent > 100 || !/^\d{1,3}(\.\d{1,4})?$/.test(draft.percent.trim())) { setError('El porcentaje debe ser mayor a 0 y hasta 100, con un máximo de 4 decimales.'); return }
    if (draft.kind === 'LINE' && !draft.productId) { setError('Seleccioná un producto para la regla por línea.'); return }
    if (!Number.isInteger(priority) || priority < -1000 || priority > 1000) { setError('La prioridad debe ser un número entero entre -1000 y 1000.'); return }
    if (draft.validUntil && draft.validFrom && draft.validUntil < draft.validFrom) { setError('La fecha final no puede ser anterior a la fecha inicial.'); return }

    saveMutation.mutate({
      code,
      description,
      kind: draft.kind,
      percent,
      customerId: draft.customerId || null,
      priceListId: draft.priceListId || null,
      productId: draft.kind === 'LINE' ? draft.productId : null,
      validFrom: draft.validFrom || null,
      validUntil: draft.validUntil || null,
      priority,
    })
  }

  const rules = rulesQuery.data?.content ?? []
  const rows: Row[] = rules.map((rule) => ({
    id: rule.id,
    code: rule.code,
    description: rule.description,
    kind: rule.kind === 'LINE' ? 'Por línea' : 'Por pedido',
    scope: [rule.customerName, rule.priceListCode, rule.productName].filter(Boolean).join(' · ') || 'General',
    percent: `${rule.percent}%`,
    priority: String(rule.priority),
    validity: `${rule.validFrom}${rule.validUntil ? ` a ${rule.validUntil}` : ' · Sin vencimiento'}`,
    status: rule.status,
  }))
  const columns: TableColumn[] = [
    { key: 'code', label: 'Código', emphasis: true },
    { key: 'description', label: 'Regla' },
    { key: 'kind', label: 'Aplicación' },
    { key: 'scope', label: 'Alcance' },
    { key: 'percent', label: 'Descuento', align: 'right' },
    { key: 'priority', label: 'Prioridad', align: 'right' },
    { key: 'validity', label: 'Vigencia' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{statusLabel(value as RuleStatus)}</Badge> },
    ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Row) => {
      const rule = rules.find((candidate) => candidate.id === row.id)
      if (!rule) return null
      return <div className="page-actions">
        <Button variant="link" onClick={() => startEdit(rule)}>Editar regla {rule.code}</Button>
        <Button variant="link" onClick={() => { setStatusRule(rule); setError('') }}>{rule.status === 'ACTIVE' ? 'Desactivar' : 'Activar'} regla {rule.code}</Button>
      </div>
    } }] : []),
  ]

  return <>
    <Panel title="Reglas de descuento" description="El backend elige y aplica las reglas vigentes al confirmar o editar el pedido." action={isAdmin ? <Button onClick={() => { setEditingRule(undefined); setDraft(emptyDraft); setShowForm((value) => !value); setError('') }}>{showForm ? 'Cerrar formulario' : '+ Nueva regla'}</Button> : undefined}>
      {feedback && <p className="success-text" role="status">{feedback}</p>}
      {error && !showForm && <p className="error-text" role="alert">{error}</p>}
      {rulesQuery.isLoading ? <EmptyState title="Cargando reglas" description="Consultando descuentos comerciales." /> : rulesQuery.isError ? <EmptyState title="No se pudieron cargar las reglas" description={rulesQuery.error.message} action={<Button variant="secondary" onClick={() => rulesQuery.refetch()}>Reintentar reglas</Button>} /> : rows.length ? <DataTable columns={columns} rows={rows} /> : <EmptyState title="Todavía no hay reglas" description="Creá reglas por línea o por pedido para automatizar descuentos comerciales." action={isAdmin ? <Button onClick={() => { setShowForm(true); setError('') }}>+ Nueva regla</Button> : undefined} />}
      {rulesQuery.data && rulesQuery.data.totalPages > 1 && <div className="pagination"><span>Página {rulesPage + 1} de {rulesQuery.data.totalPages} · {rulesQuery.data.totalElements} reglas</span><div><Button variant="secondary" aria-label="Reglas anteriores" onClick={() => setRulesPage((page) => Math.max(0, page - 1))} disabled={rulesPage === 0}>Anterior</Button><Button variant="secondary" aria-label="Siguiente reglas" onClick={() => setRulesPage((page) => Math.min((rulesQuery.data?.totalPages ?? 1) - 1, page + 1))} disabled={rulesPage + 1 >= rulesQuery.data.totalPages}>Siguiente</Button></div></div>}
    </Panel>
    {showForm && isAdmin && <Panel title={editingRule ? `Editar regla ${editingRule.code}` : 'Nueva regla'}><form className="form-grid" onSubmit={submit}>
      <label className="field"><span>Código</span><input className="input" value={draft.code} onChange={(event) => setDraft((current) => ({ ...current, code: event.target.value }))} required maxLength={40} disabled={saveMutation.isPending} /></label>
      <label className="field"><span>Descripción</span><input className="input" value={draft.description} onChange={(event) => setDraft((current) => ({ ...current, description: event.target.value }))} required maxLength={160} disabled={saveMutation.isPending} /></label>
      <label className="field"><span>Aplicación</span><select className="select" value={draft.kind} onChange={(event) => setDraft((current) => ({ ...current, kind: event.target.value as RuleKind, productId: '' }))} disabled={saveMutation.isPending}><option value="LINE">Por línea</option><option value="ORDER">Por pedido</option></select></label>
      <label className="field"><span>Porcentaje</span><input className="input" type="text" inputMode="decimal" value={draft.percent} onChange={(event) => setDraft((current) => ({ ...current, percent: event.target.value }))} required disabled={saveMutation.isPending} /></label>
      <label className="field"><span>Cliente (opcional)</span><select className="select" value={draft.customerId} onChange={(event) => setDraft((current) => ({ ...current, customerId: event.target.value }))} disabled={saveMutation.isPending || customersQuery.isError}><option value="">Todos los clientes</option>{customers.map((customer) => <option key={customer.id} value={customer.id}>{customer.name}</option>)}</select></label>
      <label className="field"><span>Lista de precios (opcional)</span><select className="select" value={draft.priceListId} onChange={(event) => setDraft((current) => ({ ...current, priceListId: event.target.value }))} disabled={saveMutation.isPending || listsQuery.isError}><option value="">Todas las listas</option>{lists.map((list) => <option key={list.id} value={list.id}>{list.code} · {list.name}</option>)}</select></label>
      {draft.kind === 'LINE' && <label className="field"><span>Producto</span><select className="select" value={draft.productId} onChange={(event) => setDraft((current) => ({ ...current, productId: event.target.value }))} required disabled={saveMutation.isPending || productsQuery.isError}><option value="">Seleccionar producto...</option>{products.map((product) => <option key={product.id} value={product.id}>{product.sku} · {product.name}</option>)}</select></label>}
      <label className="field"><span>Válida desde (opcional)</span><input className="input" type="date" value={draft.validFrom} onChange={(event) => setDraft((current) => ({ ...current, validFrom: event.target.value }))} disabled={saveMutation.isPending} /></label>
      <label className="field"><span>Válida hasta (opcional)</span><input className="input" type="date" value={draft.validUntil} onChange={(event) => setDraft((current) => ({ ...current, validUntil: event.target.value }))} disabled={saveMutation.isPending} /></label>
      <label className="field"><span>Prioridad</span><input className="input" type="number" min={-1000} max={1000} step={1} value={draft.priority} onChange={(event) => setDraft((current) => ({ ...current, priority: event.target.value }))} disabled={saveMutation.isPending} /></label>
      {error && <p className="error-text" role="alert">{error}</p>}
      {(customersQuery.isError || listsQuery.isError || (draft.kind === 'LINE' && productsQuery.isError)) && <div role="alert"><p className="error-text">No se pudieron cargar todas las opciones del formulario. Reintentá las consultas.</p><div className="page-actions">{customersQuery.isError && <Button variant="link" type="button" onClick={() => customersQuery.refetch()}>Reintentar clientes</Button>}{listsQuery.isError && <Button variant="link" type="button" onClick={() => listsQuery.refetch()}>Reintentar listas</Button>}{draft.kind === 'LINE' && productsQuery.isError && <Button variant="link" type="button" onClick={() => productsQuery.refetch()}>Reintentar productos</Button>}</div></div>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={resetForm} disabled={saveMutation.isPending}>Cancelar</Button><Button type="submit" disabled={saveMutation.isPending}>{saveMutation.isPending ? 'Guardando...' : 'Guardar regla'}</Button></div>
    </form></Panel>}
    {statusRule && <div role="dialog" aria-modal="true" aria-labelledby="rule-status-title" className="modal-backdrop"><Panel title="Confirmar cambio de regla"><h2 id="rule-status-title">¿{statusRule.status === 'ACTIVE' ? 'Desactivar' : 'Activar'} la regla {statusRule.code}?</h2><p>Los pedidos ya confirmados conservan sus descuentos originales.</p><div className="page-actions"><Button variant="secondary" onClick={() => setStatusRule(undefined)} disabled={statusMutation.isPending}>Cancelar</Button><Button onClick={() => statusMutation.mutate(statusRule)} disabled={statusMutation.isPending}>{statusMutation.isPending ? 'Guardando...' : 'Confirmar cambio'}</Button></div></Panel></div>}
  </>
}
