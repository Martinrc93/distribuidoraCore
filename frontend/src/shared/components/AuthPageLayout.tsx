import type { ReactNode } from 'react'
import { TextSizeToggle } from './TextSizeToggle'
import { ThemeToggle } from './ThemeToggle'

export function AuthPageLayout({ children }: { children: ReactNode }) {
  return <main className="login-page">
    <div className="login-controls"><TextSizeToggle /><ThemeToggle /></div>
    <div className="login-content">{children}</div>
  </main>
}
