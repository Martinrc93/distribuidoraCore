import { Button } from '../shared/components/Button'
import { PageHeader } from '../shared/components/PageHeader'
import { Panel } from '../shared/components/Panel'
import { ThemeToggle } from '../shared/components/ThemeToggle'

export function InstanceLauncher() {
  return <main className="instance-launcher">
    <div className="instance-launcher-heading">
      <PageHeader eyebrow="Desarrollo" title="Seleccionar sistema" description="Cada sistema tiene sus propios datos y su propia sesión." />
      <ThemeToggle />
    </div>
    <div className="instance-launcher-options">
      {['A', 'B'].map((instance) => <Panel key={instance} title={`Sistema ${instance}`}>
        <p className="instance-launcher-description">Accede al sistema {instance} para trabajar con sus datos.</p>
        <Button href={`/${instance.toLowerCase()}/`} fullWidth>Abrir sistema {instance}</Button>
      </Panel>)}
    </div>
  </main>
}
