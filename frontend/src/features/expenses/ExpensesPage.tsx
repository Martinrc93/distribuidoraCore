import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { apiGet, apiPost, type ApiPage } from '../../shared/api/client'
import { createIdempotencyKey } from '../../shared/api/createIdempotencyKey'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { Button } from '../../shared/components/Button'
import { DataTable } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { FormModal } from '../../shared/components/FormModal'
import { PageHeader } from '../../shared/components/PageHeader'
import { Pagination } from '../../shared/components/Pagination'
import { Panel } from '../../shared/components/Panel'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { useUrlListState } from '../../shared/useUrlListState'
import { displayDate, parseDate, todayInArgentina } from '../../shared/dates'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import { expenseColumns, expenseRow, expenseRows, expenseTypes, type Expense } from './expenses'
import { normalizeDecimalString } from '../../shared/decimal'

type Draft = { amount: number; type: string; observation: string }
type Submission = Draft & { idempotencyKey: string }
const filterKeys = ['type', 'dateMin', 'dateMax']

function ExpenseDetail({ expense, onClose }: { expense: Expense; onClose: () => void }) {
  const { date, type, amount, observation } = expenseRow(expense)
  return <FormModal size="compact" labelledBy="expense-detail-title" onClose={onClose}>
    <Panel>
      <div className="panel-header"><h2 id="expense-detail-title">Detalle del gasto</h2></div>
      <dl className="m-0 grid grid-cols-2 gap-[14px] text-[13px] [&_dt]:mb-[4px] [&_dt]:font-semibold [&_dt]:text-muted [&_dd]:m-0">
        <div><dt>Tipo</dt><dd>{type}</dd></div>
        <div><dt>Fecha de carga</dt><dd>{date}</dd></div>
        <div className="col-span-full"><dt>Importe</dt><dd className="currency-value">{amount}</dd></div>
        <div className="col-span-full"><dt>Observación</dt><dd className="whitespace-pre-wrap [overflow-wrap:anywhere]">{expense.observation ? observation : 'Sin observación'}</dd></div>
      </dl>
      <div className="page-actions mt-[18px] justify-end">
        <Button type="button" variant="secondary" onClick={onClose}>Cerrar</Button>
      </div>
    </Panel>
  </FormModal>
}

function ExpenseForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [amount, setAmount] = useState('')
  const [type, setType] = useState('')
  const [observation, setObservation] = useState('')
  const [invalidAmount, setInvalidAmount] = useState(false)
  const amountInput = useRef<HTMLInputElement>(null)
  const submission = useRef<Submission | null>(null)
  const save = useMutation({
    mutationFn: (input: Submission) => apiPost<Expense>('/api/expenses', input),
    onSuccess: onSaved,
  })
  const { requestDiscard, discardDialog } = useDiscardChanges({
    hasChanges: Boolean(amount || type || observation), disabled: save.isPending, onDiscard: onClose,
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (save.isPending) return
    const value = normalizeDecimalString(amount)
    const normalized = value === null ? Number.NaN : Number(value)
    if (value === null || !/^\d{1,12}(?:\.\d{1,2})?$/.test(value) || normalized <= 0) {
      setInvalidAmount(true)
      amountInput.current?.focus()
      return
    }
    const draft: Draft = { amount: normalized, type, observation: observation.trim() }
    const previous = submission.current
    if (previous && previous.amount === draft.amount && previous.type === draft.type
      && previous.observation === draft.observation) {
      save.mutate(previous)
    } else {
      const input = { ...draft, idempotencyKey: createIdempotencyKey() }
      submission.current = input
      save.mutate(input)
    }
  }

  return <>
    <FormModal size="compact" labelledBy="expense-form-title" busy={save.isPending} onClose={requestDiscard}>
      <Panel>
        <div className="panel-header"><h2 id="expense-form-title">Nuevo gasto</h2></div>
        <form className="grid gap-[14px]" onSubmit={submit}>
          <label className="field">
            <span>Importe ($)</span>
            <input ref={amountInput} className="input currency-input" inputMode="decimal" value={amount} required
              disabled={save.isPending} aria-invalid={invalidAmount} aria-describedby={invalidAmount ? 'expense-amount-error' : undefined}
              onChange={(event) => { setAmount(event.target.value); setInvalidAmount(false) }} />
          </label>
          {invalidAmount && <p id="expense-amount-error" className="error-text m-0" role="alert">
            Ingresá un importe mayor a cero, de hasta 12 dígitos enteros y dos decimales.
          </p>}
          <label className="field">
            <span>Tipo</span>
            <select className="select" value={type} onChange={(event) => setType(event.target.value)} disabled={save.isPending} required>
              <option value="">Seleccioná un tipo</option>
              {Object.entries(expenseTypes).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </label>
          <label className="field">
            <span>Observación (opcional)</span>
            <textarea className="input textarea font-sans focus-visible:[outline:3px_solid_#bb8754] focus-visible:outline-offset-[2px]"
              value={observation} onChange={(event) => setObservation(event.target.value)} maxLength={500} disabled={save.isPending} />
          </label>
          {save.isError && <p className="error-text m-0" role="alert">{save.error.message}</p>}
          <div className="page-actions justify-end">
            <Button type="button" variant="secondary" onClick={requestDiscard} disabled={save.isPending}>Cancelar</Button>
            <Button type="submit" disabled={save.isPending}>{save.isPending ? 'Guardando…' : 'Guardar gasto'}</Button>
          </div>
        </form>
      </Panel>
    </FormModal>
    {discardDialog}
  </>
}

export default function ExpensesPage() {
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(filterKeys, 50)
  const [params, setParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [today] = useState(() => displayDate(todayInArgentina()))
  const [formOpen, setFormOpen] = useState(false)
  const [saved, setSaved] = useState(false)
  const [detail, setDetail] = useState<Expense | null>(null)

  const selectedType = getFilter('type')
  const type = Object.hasOwn(expenseTypes, selectedType) ? selectedType : ''
  const useDefaultDates = !params.has('dateMin') && !params.has('dateMax')
  const dateMin = useDefaultDates ? today : getFilter('dateMin')
  const dateMax = useDefaultDates ? today : getFilter('dateMax')
  const from = parseDate(dateMin)
  const to = parseDate(dateMax)
  const invalidFrom = Boolean(dateMin && !from)
  const invalidTo = Boolean(dateMax && !to)
  const invalidRange = Boolean(from && to && from > to)
  const dateError = invalidFrom || invalidTo ? 'Ingresá fechas válidas con formato dd/mm/aaaa.'
    : invalidRange ? 'La fecha inicial no puede ser posterior a la final.' : ''
  const hasFilters = Boolean(type || dateMin || dateMax)
  const emptyMessage = page ? 'No hay gastos en esta página.'
    : useDefaultDates && !type ? 'No hay gastos registrados hoy.'
    : hasFilters ? 'No hay gastos para los filtros seleccionados.' : 'No hay gastos registrados.'
  const queryParams = new URLSearchParams({ page: String(page), size: String(pageSize) })
  if (type) queryParams.set('type', type)
  if (from) queryParams.set('dateMin', from)
  if (to) queryParams.set('dateMax', to)
  const path = `/api/expenses?${queryParams}`
  const expenses = useQuery({
    queryKey: queryKeys.expenses.list(path),
    queryFn: ({ signal }) => apiGet<ApiPage<Expense>>(path, signal),
    enabled: !dateError,
    retry: false,
  })

  function setDateFilter(key: 'dateMin' | 'dateMax', value: string) {
    const next = new URLSearchParams(params)
    next.set('dateMin', key === 'dateMin' ? value : dateMin)
    next.set('dateMax', key === 'dateMax' ? value : dateMax)
    next.delete('page')
    setParams(next, { replace: true })
  }

  function clearFilters() {
    const next = new URLSearchParams(params)
    next.delete('type')
    next.set('dateMin', '')
    next.set('dateMax', '')
    next.delete('page')
    setParams(next, { replace: true })
  }

  function openDetail(id: string) {
    const expense = expenses.data?.content.find((entry) => entry.id === id)
    if (!expense) return
    document.getElementById(`expense-detail-${id}`)?.focus({ preventScroll: true })
    setDetail(expense)
  }

  const columns = [...expenseColumns, {
    key: 'detail', label: 'Acciones',
    render: (_: string, row: Record<string, string>) => <Button id={`expense-detail-${row.id}`} type="button" variant="link"
      aria-label={`Ver detalle de ${row.type} del ${row.date}`} aria-haspopup="dialog"
      onClick={(event) => { event.stopPropagation(); openDetail(row.id) }}>Ver detalle</Button>,
  }]

  function onSaved() {
    setFormOpen(false)
    setSaved(true)
    if (page > 0) setPage(0)
    void invalidateDomains(queryClient, 'expenses', 'reports')
  }

  return <>
    <PageHeader title="Gastos" actions={<Button type="button" onClick={() => {
      setSaved(false)
      setFormOpen(true)
    }}>Nuevo gasto</Button>} />
    <Panel title="Gastos registrados">
      <div className="toolbar items-end">
        <label className="field min-w-0 [@media(max-width:640px)]:w-full">
          <span>Tipo de gasto</span>
          <select className="select [@media(max-width:640px)]:min-w-0" value={type} onChange={(event) => setFilter('type', event.target.value)}>
            <option value="">Todos los tipos</option>
            {Object.entries(expenseTypes).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <div className="orders-date-filters">
          <OrderDateFilter id="expenses-date-min" label="Desde" value={dateMin} isoValue={from ?? ''}
            invalid={invalidFrom || invalidRange} describedBy={dateError ? 'expenses-date-error' : undefined} onChange={(value) => setDateFilter('dateMin', value)} />
          <OrderDateFilter id="expenses-date-max" label="Hasta" value={dateMax} isoValue={to ?? ''}
            invalid={invalidTo || invalidRange} describedBy={dateError ? 'expenses-date-error' : undefined} onChange={(value) => setDateFilter('dateMax', value)} />
        </div>
        <Button type="button" variant="secondary" disabled={!hasFilters} onClick={clearFilters}>Limpiar filtros</Button>
      </div>
      {saved && <p className="success-text" role="status">Gasto registrado.</p>}
      {dateError ? <p id="expenses-date-error" className="error-text" role="alert">{dateError}</p>
        : expenses.isLoading ? <EmptyState state="loading" title="Cargando gastos" description="Consultando los gastos registrados." />
        : expenses.isError ? <EmptyState state="error" title="No se pudieron cargar los gastos" description={expenses.error.message}
          action={<Button type="button" variant="secondary" onClick={() => void expenses.refetch()}>Reintentar</Button>} />
        : expenses.data && <>
        <DataTable className="[&_td[data-label='Observación']]:whitespace-normal [&_td[data-label='Observación']]:[overflow-wrap:anywhere]"
          columns={columns} rows={expenseRows(expenses.data.content)}
          emptyContent={<p className="table-empty-message">{emptyMessage}</p>} />
        {(page > 0 || expenses.data.totalPages > 1) && <Pagination label="Paginación de gastos"
          summary={<>Página {page + 1} · {expenses.data.totalElements} gastos</>}
          onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)}
          previousDisabled={!page} nextDisabled={page + 1 >= expenses.data.totalPages} />}
      </>}
    </Panel>
    {formOpen && <ExpenseForm onClose={() => setFormOpen(false)} onSaved={onSaved} />}
    {detail && <ExpenseDetail expense={detail} onClose={() => setDetail(null)} />}
  </>
}
