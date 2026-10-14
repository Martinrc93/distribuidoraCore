import type { ReactNode } from 'react'
import { CurrencyText } from './CurrencyText'

export function EmptyState({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return <div className="empty-state"><div className="empty-icon">—</div><h3>{title}</h3><p><CurrencyText>{description}</CurrencyText></p>{action}</div>
}
