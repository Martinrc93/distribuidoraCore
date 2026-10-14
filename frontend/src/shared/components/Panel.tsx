import type { ReactNode } from 'react'
import { CurrencyText } from './CurrencyText'

export function Panel({ title, description, action, children }: { title?: string; description?: string; action?: ReactNode; children: ReactNode }) {
  return <section className="panel">{(title || description || action) && <div className="panel-header"><div>{title && <h2>{title}</h2>}{description && <p><CurrencyText>{description}</CurrencyText></p>}</div>{action}</div>} {children}</section>
}
