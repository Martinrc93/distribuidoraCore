import { Component, Suspense, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { Button } from '../shared/components/Button'
import { EmptyState } from '../shared/components/EmptyState'
import { Panel } from '../shared/components/Panel'

class RouteErrorBoundary extends Component<{ children: ReactNode; pathname: string }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidUpdate(previous: Readonly<{ pathname: string }>) {
    if (this.state.failed && previous.pathname !== this.props.pathname) this.setState({ failed: false })
  }

  render() {
    if (this.state.failed) {
      return <Panel><EmptyState
        state="error"
        title="No se pudo abrir la página"
        description="Reintentá la carga o volvé al inicio."
        action={<div className="page-actions">
          <Button type="button" onClick={() => window.location.reload()}>Reintentar</Button>
          <Button href="/" variant="secondary">Volver al inicio</Button>
        </div>}
      /></Panel>
    }
    return this.props.children
  }
}

export function RouteContent({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return <RouteErrorBoundary pathname={pathname}>
    <Suspense fallback={<Panel><EmptyState state="loading" title="Cargando página" description="La página se está preparando." /></Panel>}>
      {children}
    </Suspense>
  </RouteErrorBoundary>
}
