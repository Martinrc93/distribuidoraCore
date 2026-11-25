import { useSyncExternalStore } from 'react'
import { getTextSize, subscribeToTextSize, toggleTextSize } from '../textSize'
import { Button } from './Button'

export function TextSizeToggle() {
  const large = useSyncExternalStore(subscribeToTextSize, getTextSize) === 'large'
  const label = large ? 'Usar texto de tamaño normal' : 'Agrandar el texto'

  return <Button type="button" variant="secondary" className="text-size-toggle" onClick={toggleTextSize} aria-label={label} aria-pressed={large} title={label}>
    <span aria-hidden="true" className="text-size-toggle-icon">A<small>A</small></span>
    <span className="text-size-toggle-label">{large ? 'Texto normal' : 'Texto grande'}</span>
  </Button>
}
