import { useState } from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerSelect, SellerSelect, SupplierSelect } from './EntitySelect'

afterEach(cleanup)

describe('shared customer and seller selection', () => {
  it.each([
    { Component: CustomerSelect, label: 'Buscar por cliente', empty: 'Todos los clientes' },
    { Component: SellerSelect, label: 'Buscar por vendedor', empty: 'Todos los vendedores' },
    { Component: SupplierSelect, label: 'Buscar por proveedor', empty: 'Todos los proveedores' },
  ])('uses the same name search, keyboard selection and cancellation for $label', async ({ Component, label, empty }) => {
    const user = userEvent.setup()
    const changed = vi.fn()
    function Example() {
      const [value, setValue] = useState('')
      return <><Component options={[{ id: 'one', name: 'Lucía' }, { id: 'two', name: 'Martín' }]} value={value}
        onChange={(id) => { changed(id); setValue(id) }} /><button>Next</button></>
    }
    render(<Example />)
    const input = screen.getByRole('combobox', { name: label })
    expect(input).toHaveValue(empty)
    await user.click(input)
    await user.type(input, 'LUCIA', { skipClick: true })
    expect(screen.getByRole('option', { name: 'Lucía' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Martín' })).not.toBeInTheDocument()
    expect(changed).not.toHaveBeenCalled()
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(changed).toHaveBeenLastCalledWith('one')
    expect(input).toHaveValue('Lucía')
    await user.click(input)
    await user.type(input, 'another name', { skipClick: true })
    await user.tab()
    expect(input).toHaveValue('Lucía')
    expect(changed).toHaveBeenCalledTimes(1)
    await user.click(input)
    await user.click(screen.getByRole('option', { name: empty }))
    expect(changed).toHaveBeenLastCalledWith('')
  })

  it('requires a confirmed ID in a form even when search text is present', async () => {
    const user = userEvent.setup()
    const submit = vi.fn()
    function Example() {
      const [value, setValue] = useState('')
      return <form onSubmit={(event) => { event.preventDefault(); submit(value) }}>
        <CustomerSelect mode="selection" label="Cliente" options={[{ id: 'customer-1', name: 'Almacén Norte' }]} value={value} onChange={setValue} required />
        <button type="submit">Save</button>
      </form>
    }
    render(<Example />)
    const input = screen.getByRole('combobox', { name: 'Cliente' }) as HTMLInputElement
    expect(input).toHaveAttribute('aria-required', 'true')
    await user.click(input)
    await user.type(input, 'Almacén', { skipClick: true })
    expect(input.checkValidity()).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(submit).not.toHaveBeenCalled()
    await user.click(input)
    await user.click(screen.getByRole('option', { name: 'Almacén Norte' }))
    expect(input.checkValidity()).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(submit).toHaveBeenCalledWith('customer-1')
  })

  it('keeps the empty selection meaning and exposes shared loading and error states', async () => {
    const props = { mode: 'selection' as const, label: 'Vendedor', options: [], value: '', onChange: vi.fn(), emptyLabel: 'Usar vendedor del cliente' }
    const { rerender } = render(<SellerSelect {...props} loading />)
    const input = screen.getByRole('combobox', { name: 'Vendedor' })
    expect(input).toHaveValue('Cargando vendedores…')
    expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Abrir opciones: Vendedor' })).toBeDisabled()
    expect(input.closest('.searchable-select-full')).toBeInTheDocument()
    rerender(<SellerSelect {...props} />)
    expect(input).toHaveValue('Usar vendedor del cliente')
    await userEvent.setup().click(input)
    expect(screen.getByRole('option', { name: 'Usar vendedor del cliente' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('No se encontraron coincidencias.')
    rerender(<SellerSelect {...props} value="missing" invalid describedBy="seller-error" />)
    await userEvent.setup().keyboard('{Escape}')
    await waitFor(() => expect(input).toHaveValue('Vendedor no disponible'))
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'seller-error')
  })
})
