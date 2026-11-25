import type { ReactNode } from 'react'
import { CurrencyText } from './CurrencyText'

type Props = {
  title: string
  description: string
  action?: ReactNode
  state?: 'empty' | 'loading' | 'error'
  compact?: boolean
}

export function EmptyState({ title, description, action, state = 'empty', compact = false }: Props) {
  return <div className={`empty-state${compact ? ' empty-state-compact' : ''}`}>
    <div role={state === 'loading' ? 'status' : state === 'error' ? 'alert' : undefined}>
      <div className={`empty-icon empty-icon-${state}`} aria-hidden="true">{state === 'error' ? '!' : state === 'empty' ? '—' : null}</div>
      <h3>{title}</h3><p><CurrencyText>{description}</CurrencyText></p>
    </div>
    {action}
  </div>
}
