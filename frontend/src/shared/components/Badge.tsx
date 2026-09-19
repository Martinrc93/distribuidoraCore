import type { ReactNode } from 'react'

export type BadgeTone = 'strong' | 'soft' | 'muted'

export function Badge({ children, tone = 'soft' }: { children: ReactNode; tone?: BadgeTone }) {
  return <span className={`badge badge-${tone}`}>{children}</span>
}
