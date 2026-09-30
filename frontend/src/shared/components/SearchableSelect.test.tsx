import { useState } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SearchableSelect } from './SearchableSelect'

afterEach(cleanup)

describe('SearchableSelect', () => {
  it('keeps distinct IDs for repeated names and cancels uncommitted text on blur', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    function Example() {
      const [value, setValue] = useState('')
      return <><SearchableSelect label="Cliente" options={[{ id: 'first', name: 'José' }, { id: 'second', name: 'José' }]} value={value}
        onChange={(id) => { setValue(id); onChange(id) }} allLabel="Todos" unavailableLabel="No disponible" loadingLabel="Cargando…" /><button type="button">Siguiente campo</button></>
    }
    render(<Example />)
    const input = screen.getByRole('combobox', { name: 'Cliente' })
    await user.click(input)
    await user.type(input, 'jose', { skipClick: true })
    const options = screen.getAllByRole('option', { name: 'José' })
    expect(options).toHaveLength(2)
    await user.click(options[1])
    expect(onChange).toHaveBeenLastCalledWith('second')
    await user.click(input)
    expect(screen.getAllByRole('option', { name: 'José' })[1]).toHaveAttribute('aria-selected', 'true')
    await user.type(input, 'no existe', { skipClick: true })
    await user.tab()
    expect(input).toHaveValue('José')
    expect(input).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: 'Siguiente campo' })).toHaveFocus()
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('allows clearing an unavailable selection and closing the list with an outside click', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<><SearchableSelect label="Cliente" options={[]} value="missing" onChange={onChange} allLabel="Todos" unavailableLabel="No disponible" loadingLabel="Cargando…" /><button type="button">Fuera</button></>)
    const input = screen.getByRole('combobox')
    expect(input).toHaveValue('No disponible')
    await user.click(screen.getByRole('button', { name: 'Abrir opciones: Cliente' }))
    expect(screen.getByRole('option', { name: 'Todos' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Fuera' }))
    expect(input).toHaveAttribute('aria-expanded', 'false')
    await user.click(input)
    await user.keyboard('{ArrowUp}{Enter}')
    expect(onChange).toHaveBeenCalledWith('')
  })
})
