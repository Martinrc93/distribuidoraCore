import type { ReactNode } from 'react'
import { hasCurrencyAmount } from './CurrencyText'

export type TableColumn<Row extends Record<string, string> = Record<string, string>> = {
  key: string
  label: string
  align?: 'left' | 'right'
  emphasis?: boolean
  render?: (value: string, row: Row) => ReactNode
}

type DataTableProps<Row extends Record<string, string>> = {
  columns: TableColumn<Row>[]
  rows: Row[]
  /** @deprecated Use an explicit link or button in an actions column. */
  onRowClick?: (row: Row) => void
  className?: string
  emptyContent?: ReactNode
}

export function DataTable<Row extends Record<string, string>>({
  columns, rows, onRowClick, className, emptyContent,
}: DataTableProps<Row>) {
  return (
    <div className={`table-wrap${className ? ` ${className}` : ''}`}>
      <table className="data-table">
        <thead><tr>{columns.map((column) => <th scope="col" className={column.align === 'right' ? 'align-right' : ''} key={column.key}>{column.label}</th>)}</tr></thead>
        <tbody>
          {rows.length === 0 && emptyContent != null && <tr className="table-empty-row"><td className="table-empty-cell" colSpan={columns.length}>{emptyContent}</td></tr>}
          {rows.map((row, index) => <tr className={onRowClick ? 'clickable-row' : ''} key={`${row.id ?? row.name ?? 'row'}-${index}`} onClick={() => onRowClick?.(row)}>
            {columns.map((column) => <td data-label={column.label} className={`${column.align === 'right' ? 'align-right' : ''}${column.emphasis ? ' cell-emphasis' : ''}${hasCurrencyAmount(row[column.key] ?? '') ? ' currency-value' : ''}`} key={column.key}>{column.render ? column.render(row[column.key] ?? '', row) : row[column.key] ?? '-'}</td>)}
          </tr>)}
        </tbody>
      </table>
    </div>
  )
}
