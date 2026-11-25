import type { TableColumn } from '../../shared/components/DataTable'
import { formatBusinessDateTimeShort } from '../../shared/dates'
import { formatMoney } from '../../shared/format'

export const expenseTypes = {
  PETTY_CASH: 'Caja chica',
  FUEL: 'Combustible',
  SERVICES: 'Servicios',
  OTHER: 'Otros',
}

export type Expense = {
  id: string
  amount: number
  type: keyof typeof expenseTypes
  observation: string | null
  createdAt: string
}

export const expenseMoney = { format: (value: unknown) => formatMoney(value, 2) }

export const expenseColumns: TableColumn[] = [
  { key: 'date', label: 'Fecha de carga' },
  { key: 'type', label: 'Tipo', emphasis: true },
  { key: 'amount', label: 'Importe', align: 'right' },
  { key: 'observation', label: 'Observación' },
]

export function expenseRow(entry: Expense) {
  return {
    id: entry.id,
    date: formatBusinessDateTimeShort(entry.createdAt),
    type: expenseTypes[entry.type],
    amount: expenseMoney.format(entry.amount),
    observation: entry.observation ?? '—',
  }
}

export function expenseRows(entries: Expense[]) {
  return entries.map(expenseRow)
}
