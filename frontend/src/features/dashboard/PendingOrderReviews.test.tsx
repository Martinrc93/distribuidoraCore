import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { PendingOrderReviews } from './PendingOrderReviews'

function setup(authorities = ['ADMIN_ALL']) {
  sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><PendingOrderReviews /></QueryClientProvider>)
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear() })

it('shows seller orders from all dates and confirms review independently of delivered status', async () => {
  const user = userEvent.setup()
  let reviewed = false
  const fetch = vi.spyOn(global, 'fetch').mockImplementation((_input, init) => {
    if (init?.method === 'POST') { reviewed = true; return Promise.resolve({ ok: true, status: 204 } as Response) }
    return Promise.resolve({ ok: true, status: 200, json: async () => ({ content: reviewed ? [] : [{ id: 'o1', number: 'PED-ANTERIOR', customer: 'Cliente', seller: 'Vendedor', status: 'DELIVERED', reviewPending: true }], totalElements: reviewed ? 0 : 8 }) } as Response)
  })
  setup()
  expect(await screen.findByText('Pedidos de vendedores por revisar (8)')).toBeInTheDocument()
  expect(fetch).toHaveBeenCalledWith('/api/orders?page=0&size=5&reviewPending=true', expect.anything())
  expect(screen.getByRole('link', { name: 'Ver todos los pendientes' })).toHaveAttribute('href', '/orders?review=PENDING&dateMin=&dateMax=')
  await user.click(screen.getByRole('button', { name: 'Confirmar revisión del pedido PED-ANTERIOR' }))
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar revisión' }))
  expect(await screen.findByText('No hay pedidos pendientes de revisión.')).toBeInTheDocument()
  expect(screen.queryByText('Vendedor · Pendiente de revisión')).not.toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('link', { name: 'Ver todos los pendientes' })).toHaveFocus())
})

it('does not fetch or render the administrator panel for sellers', () => {
  const fetch = vi.spyOn(global, 'fetch')
  setup(['ORDER_CREATE', 'ORDER_PREPARE'])
  expect(screen.queryByRole('heading')).not.toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})

it('announces errors and allows retry', async () => {
  const user = userEvent.setup()
  vi.spyOn(global, 'fetch').mockRejectedValueOnce(new Error('Offline')).mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ content: [], totalElements: 0 }) } as Response)
  setup()
  expect(await screen.findByRole('alert')).toHaveTextContent('No se pudieron cargar')
  await user.click(screen.getByRole('button', { name: 'Reintentar revisión' }))
  expect(await screen.findByText('No hay pedidos pendientes de revisión.')).toBeInTheDocument()
})
