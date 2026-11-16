import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OrderOperationalActions } from './OrderOperationalActions'

const order = { id: 'o1', number: 'PED-1', status: 'CONFIRMED', fulfillmentStatus: 'PENDING', reviewPending: true }
function setup(authorities: string[], value = order) {
  sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><OrderOperationalActions order={value} /></QueryClientProvider>)
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear() })

describe('OrderOperationalActions', () => {
  it.each([['ORDER_CREATE', false], ['SALE_DELIVER', false], ['ORDER_PREPARE', true], ['ADMIN_ALL', true]])('uses an independent preparation permission: %s', (permission, allowed) => {
    setup([String(permission)])
    expect(screen.queryByRole('button', { name: 'Preparar pedido PED-1' }) !== null).toBe(allowed)
    expect(screen.queryByRole('button', { name: 'Confirmar revisión del pedido PED-1' }) !== null).toBe(permission === 'ADMIN_ALL')
  })

  it('lets sellers prepare without an administrative review and keeps errors retryable', async () => {
    const user = userEvent.setup()
    const fetch = vi.spyOn(global, 'fetch').mockResolvedValueOnce({ ok: false, status: 409, json: async () => ({ detail: 'El producto no está activo' }) } as Response)
      .mockResolvedValueOnce({ ok: true, status: 204 } as Response)
    setup(['ORDER_PREPARE'])
    const trigger = screen.getByRole('button', { name: 'Preparar pedido PED-1' })
    await user.click(trigger)
    let dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('button', { name: 'Volver' })).toHaveFocus()
    await user.click(within(dialog).getByRole('button', { name: 'Confirmar preparación' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El producto no está activo')
    dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Confirmar preparación' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch).toHaveBeenLastCalledWith('/api/orders/o1/prepare', expect.objectContaining({ method: 'POST' }))
    expect(await screen.findByRole('status')).toHaveTextContent('preparado')
    expect(trigger).toHaveFocus()
  })

  it('allows admin review of delivered orders and never offers preparation for them', async () => {
    const user = userEvent.setup()
    const fetch = vi.spyOn(global, 'fetch').mockResolvedValue({ ok: true, status: 204 } as Response)
    setup(['ADMIN_ALL'], { ...order, status: 'DELIVERED', fulfillmentStatus: 'PREPARED' })
    expect(screen.queryByRole('button', { name: 'Preparar pedido PED-1' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirmar revisión del pedido PED-1' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar revisión' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Revisión del pedido PED-1 confirmada')
    expect(fetch).toHaveBeenCalledWith('/api/orders/o1/review', expect.objectContaining({ method: 'POST' }))
  })
})
