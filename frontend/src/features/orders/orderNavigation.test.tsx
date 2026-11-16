import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { OrderNavigator, saveOrderNavigation } from './orderNavigation'

function Detail() {
  const { orderId = '' } = useParams()
  return <><h1>Pedido {orderId}</h1><input aria-label="Nota" /><OrderNavigator orderId={orderId} /></>
}

function renderAt(orderId: string) {
  render(<MemoryRouter initialEntries={[`/orders/${orderId}`]}><Routes><Route path="/orders/:orderId" element={<Detail />} /></Routes></MemoryRouter>)
}

afterEach(() => { cleanup(); sessionStorage.clear() })

describe('OrderNavigator', () => {
  it('moves between the orders of the list with buttons and arrow keys, but not while typing', async () => {
    saveOrderNavigation({ ids: ['a', 'b', 'c'], numbers: ['PED-1', 'PED-2', 'PED-3'], returnTo: '/orders?status=CONFIRMED' })
    const user = userEvent.setup()
    renderAt('b')

    expect(screen.getByText('2 de 3')).toBeInTheDocument()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('heading', { name: 'Pedido c' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'No hay pedido siguiente' })).toBeDisabled()

    await user.click(screen.getByLabelText('Nota'))
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByRole('heading', { name: 'Pedido c' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Pedido anterior PED-2' }))
    expect(screen.getByRole('heading', { name: 'Pedido b' })).toBeInTheDocument()
  })

  it('renders nothing when the order was not opened from the list', () => {
    renderAt('z')
    expect(screen.queryByRole('navigation', { name: 'Navegar entre pedidos' })).not.toBeInTheDocument()
  })
})
