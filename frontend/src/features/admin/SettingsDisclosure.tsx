import type { ReactNode } from 'react'
import { Badge } from '../../shared/components/Badge'

export function SettingsDisclosure({ group, title, description, status, children }: {
  group: string
  title: string
  description: string
  status?: string
  children: ReactNode
}) {
  return <details className="panel settings-disclosure p-0" name={group}>
    <summary>
      <span>{title}</span>
      <span className="ml-auto flex shrink-0 items-center gap-[8px]">
        <span role="status">{status && <Badge tone="muted">{status}</Badge>}</span>
        <svg className="settings-disclosure-chevron" width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <path d="m5 8 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </span>
    </summary>
    <div className="p-[20px] [border-top:1px_solid_var(--line)]">
      <p className="mb-[16px] text-[13px] text-muted">{description}</p>
      {children}
    </div>
  </details>
}
