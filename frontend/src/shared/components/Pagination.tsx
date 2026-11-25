import type { ReactNode } from 'react'
import { Button } from './Button'

type Props = {
  label?: string
  summary: ReactNode
  onPrevious: () => void
  onNext: () => void
  previousDisabled?: boolean
  nextDisabled?: boolean
  previousLabel?: string
  nextLabel?: string
}

export function Pagination({
  label = 'Paginación', summary, onPrevious, onNext, previousDisabled, nextDisabled, previousLabel, nextLabel,
}: Props) {
  return <nav className="pagination" aria-label={label}>
    <span>{summary}</span>
    <div>
      <Button type="button" variant="secondary" onClick={onPrevious} disabled={previousDisabled} aria-label={previousLabel}>Anterior</Button>
      <Button type="button" variant="secondary" onClick={onNext} disabled={nextDisabled} aria-label={nextLabel}>Siguiente</Button>
    </div>
  </nav>
}
