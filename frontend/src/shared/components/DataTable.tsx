import type { ReactNode } from 'react'

export type TableColumn = {
  key: string
  label: string
  align?: 'left' | 'right'
  emphasis?: boolean
  render?: (value: string, row: Record<string, string>) => ReactNode
}

type DataTableProps = {
  columns: TableColumn[]
  rows: Record<string, string>[]
  onRowClick?: (row: Record<string, string>) => void
}

export function DataTable({ columns, rows, onRowClick }: DataTableProps) {
  return (
    <div className="table-wrap">
      <table className="data-table">
        <thead><tr>{columns.map((column) => <th className={column.align === 'right' ? 'align-right' : ''} key={column.key}>{column.label}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, index) => <tr className={onRowClick ? 'clickable-row' : ''} key={`${row.id ?? row.name ?? 'row'}-${index}`} onClick={() => onRowClick?.(row)}>
            {columns.map((column) => <td data-label={column.label} className={`${column.align === 'right' ? 'align-right' : ''}${column.emphasis ? ' cell-emphasis' : ''}`} key={column.key}>{column.render ? column.render(row[column.key] ?? '', row) : row[column.key] ?? '-'}</td>)}
          </tr>)}
        </tbody>
      </table>
    </div>
  )
}
