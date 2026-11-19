import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiPost, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { FormModal } from '../../shared/components/FormModal'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { useUrlListState } from '../../shared/useUrlListState'
import { expenseColumns, expenseRows, expenseTypes, type Expense } from './expenses'

type Draft = { amount: number; type: string; observation: string }
type Submission = Draft & { idempotencyKey: string }

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
    const value = amount.trim().replace(',', '.')
    const normalized = Number(value)
    if (!/^\d{1,12}(?:\.\d{1,2})?$/.test(value) || normalized <= 0) {
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
      const input = { ...draft, idempotencyKey: crypto.randomUUID() }
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
  const { page, pageSize, setPage } = useUrlListState([])
  const queryClient = useQueryClient()
  const [formOpen, setFormOpen] = useState(false)
  const [saved, setSaved] = useState(false)
  const expenses = useQuery({
    queryKey: ['expenses', page, pageSize],
    queryFn: ({ signal }) => apiGet<ApiPage<Expense>>(`/api/expenses?page=${page}&size=${pageSize}`, signal),
    retry: false,
  })

  function onSaved() {
    setFormOpen(false)
    setSaved(true)
    if (page > 0) setPage(0)
    void queryClient.invalidateQueries({ queryKey: ['expenses'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard', 'analytics'] })
  }

  return <>
    <PageHeader eyebrow="Operación" title="Gastos" actions={<Button type="button" onClick={() => {
      setSaved(false)
      setFormOpen(true)
    }}>Nuevo gasto</Button>} />
    <Panel title="Gastos registrados">
      {saved && <p className="success-text" role="status">Gasto registrado.</p>}
      {expenses.isLoading && <div role="status"><EmptyState title="Cargando gastos" description="Consultando los gastos registrados." /></div>}
      {expenses.isError && <div role="alert"><EmptyState title="No se pudieron cargar los gastos" description={expenses.error.message}
        action={<Button type="button" variant="secondary" onClick={() => void expenses.refetch()}>Reintentar</Button>} /></div>}
      {!expenses.isError && expenses.data && <>
        <DataTable className="[&_td:last-child]:whitespace-normal [&_td:last-child]:[overflow-wrap:anywhere]"
          columns={expenseColumns} rows={expenseRows(expenses.data.content)}
          emptyContent={<p className="table-empty-message">{page ? 'No hay gastos en esta página.' : 'No hay gastos registrados.'}</p>} />
        {(page > 0 || expenses.data.totalPages > 1) && <div className="pagination">
          <span>Página {page + 1} · {expenses.data.totalElements} gastos</span>
          <div>
            <Button type="button" variant="secondary" onClick={() => setPage(page - 1)} disabled={!page}>Anterior</Button>
            <Button type="button" variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= expenses.data.totalPages}>Siguiente</Button>
          </div>
        </div>}
      </>}
    </Panel>
    {formOpen && <ExpenseForm onClose={() => setFormOpen(false)} onSaved={onSaved} />}
  </>
}
