import { useId, useState, type ComponentPropsWithoutRef } from 'react'
import { Button } from './Button'

type Props = Omit<ComponentPropsWithoutRef<'input'>, 'id' | 'className' | 'type'> & { label: string }

export function PasswordField({ label, disabled, ...input }: Props) {
  const id = useId()
  const [visible, setVisible] = useState(false)

  return <div className="field">
    <label htmlFor={id}>{label}</label>
    <div className="password-control">
      <input {...input} id={id} className="input" type={visible ? 'text' : 'password'} disabled={disabled} spellCheck={false} autoCapitalize="none" />
      <Button type="button" variant="secondary" aria-controls={id} aria-label={`${visible ? 'Ocultar' : 'Mostrar'} ${label.toLowerCase()}`} onClick={() => setVisible(!visible)} disabled={disabled}>
        {visible ? 'Ocultar' : 'Mostrar'}
      </Button>
    </div>
  </div>
}
