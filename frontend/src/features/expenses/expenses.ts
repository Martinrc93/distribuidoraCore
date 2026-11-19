import type { TableColumn } from '../../shared/components/DataTable'
import { businessTimeZone } from '../dashboard/dashboardDates'

export const expenseTypes = {
  PETTY_CASH: 'Caja chica',
  OTHER: 'Otros',
  SERVICES: 'Servicios',
  FUEL: 'Combustible',
}

export type Expense = {
  id: string
  amount: number
  type: keyof typeof expenseTypes
  observation: string | null
  createdAt: string
}

export const expenseMoney = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' })
const expenseDate = new Intl.DateTimeFormat('es-AR', { dateStyle: 'short', timeStyle: 'short', timeZone: businessTimeZone })

export const expenseColumns: TableColumn[] = [
  { key: 'date', label: 'Fecha de carga' },
  { key: 'type', label: 'Tipo', emphasis: true },
  { key: 'amount', label: 'Importe', align: 'right' },
  { key: 'observation', label: 'Observación' },
]

export function expenseRows(entries: Expense[]) {
  return entries.map((entry) => ({
    id: entry.id,
    date: expenseDate.format(new Date(entry.createdAt)),
    type: expenseTypes[entry.type],
    amount: expenseMoney.format(entry.amount),
    observation: entry.observation ?? '—',
  }))
}
