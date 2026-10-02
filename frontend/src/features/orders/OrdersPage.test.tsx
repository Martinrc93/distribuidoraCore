import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrdersPage from './OrdersPage'

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

function renderOrders(entry = '/orders', authorities?: string[]) {
  if (authorities) sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities }))}.signature`)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[entry]}><Routes>
    <Route path="/orders" element={<OrdersPage />} />
    <Route path="/orders/:orderId" element={<h1>Detalle solicitado</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>)
}

function mockOrders() {
  return vi.spyOn(global, 'fetch').mockImplementation(() => response({
    content: [{ id: 'order-1', number: 'PED-001', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-30T02:00:00Z' }],
    page: 0, size: 20, totalElements: 21, totalPages: 2,
  }))
}

describe('OrdersPage', () => {
  it.each([['ADMIN_ALL'], ['ORDER_CREATE']])('shows direct edit links only for admins: %s', async (authority) => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response({
      content: ['CONFIRMED', 'DELIVERED', 'CANCELLED'].map((status) => ({ id: status, number: `PED-${status}`, customer: 'Almacén Norte', seller: 'Lucía', total: 300, status, date: '2026-10-01T15:00:00Z' })),
      page: 0, size: 20, totalElements: 3, totalPages: 1,
    }))
    renderOrders('/orders', [authority])
    await screen.findByText('PED-CONFIRMED')
    expect(screen.getAllByRole('link', { name: /Abrir pedido/ })).toHaveLength(3)
    if (authority === 'ADMIN_ALL') {
      expect(screen.getByRole('link', { name: 'Editar pedido PED-CONFIRMED' })).toHaveAttribute('href', '/orders/CONFIRMED?edit=true')
      expect(screen.getAllByRole('link', { name: /Editar pedido/ })).toHaveLength(1)
    } else expect(screen.queryByRole('link', { name: /Editar pedido/ })).not.toBeInTheDocument()
  })
  const dialogMethods = ['showModal', 'close'] as const
  const originalDialogMethods = dialogMethods.map((method) => Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, method))
  afterEach(() => {
    cleanup(); sessionStorage.clear(); vi.restoreAllMocks(); vi.useRealTimers()
    dialogMethods.forEach((method, index) => {
      const descriptor = originalDialogMethods[index]
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, method, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, method)
    })
  })
  beforeEach(() => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-token')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-30T12:00:00Z'))
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } })
  })

  it('lists real orders and opens their detail route', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({
      content: [{ id: 'order-1', number: 'PED-001', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-24T10:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    }))
    renderOrders()

    expect(await screen.findByText('PED-001')).toBeInTheDocument()
    expect(screen.getByText('Almacén Norte')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=&status=&dateMin=2026-09-30&dateMax=2026-09-30', expect.anything())
    expect(screen.getByLabelText('Desde')).toHaveValue('30/09/2026')
    expect(screen.getByLabelText('Hasta')).toHaveValue('30/09/2026')
    expect(screen.getByText('24/09/2026')).toBeInTheDocument()
    await screen.findByRole('link', { name: /abrir pedido PED-001/i })
  })

  it('uses the Argentine calendar day for defaults and displayed dates', async () => {
    vi.setSystemTime(new Date('2026-09-30T02:00:00Z'))
    const fetchMock = mockOrders()
    renderOrders()
    expect(await screen.findByText('29/09/2026')).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('29/09/2026')
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=2026-09-29&dateMax=2026-09-29'), expect.anything())
  })

  it('opens each calendar from the date field, calendar button and keyboard', async () => {
    mockOrders()
    renderOrders()
    await screen.findByText('PED-001')
    for (const label of ['Desde', 'Hasta']) {
      const input = screen.getByLabelText(label)
      const button = screen.getByRole('button', { name: `Abrir calendario: ${label}` })
      const openers = [() => fireEvent.click(input), () => fireEvent.click(button), () => fireEvent.keyDown(input, { key: 'Enter' }), () => fireEvent.keyDown(input, { key: 'ArrowDown', altKey: true })]
      for (const open of openers) {
        open()
        const calendar = screen.getByRole('dialog', { name: new RegExp(`Calendario: ${label}`) })
        expect(within(calendar).getByText('Septiembre de 2026')).toBeInTheDocument()
        expect(within(calendar).getByText('lu')).toBeInTheDocument()
        expect(within(calendar).getByRole('button', { name: /miércoles, 30 de septiembre de 2026/ })).toHaveFocus()
        fireEvent.click(within(calendar).getByRole('button', { name: 'Cerrar' }))
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      }
    }
  })

  it('applies calendar selections in numeric format and resets pagination', async () => {
    const fetchMock = mockOrders()
    renderOrders('/orders?page=1')
    await screen.findByText('PED-001')
    fireEvent.click(screen.getByLabelText('Desde'))
    fireEvent.click(screen.getByRole('button', { name: /15 de septiembre de 2026/ }))
    expect(screen.getByLabelText('Desde')).toHaveValue('15/09/2026')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=&status=&dateMin=2026-09-15&dateMax=2026-09-30', expect.anything()))
    fireEvent.click(screen.getByLabelText('Hasta'))
    fireEvent.click(screen.getByRole('button', { name: /25 de septiembre de 2026/ }))
    expect(screen.getByLabelText('Hasta')).toHaveValue('25/09/2026')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=2026-09-15&dateMax=2026-09-25'), expect.anything()))
    fireEvent.click(screen.getByLabelText('Desde'))
    fireEvent.click(screen.getByRole('button', { name: 'Limpiar' }))
    expect(screen.getByLabelText('Desde')).toHaveValue('')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=&dateMax=2026-09-25'), expect.anything()))
  })

  it('navigates leap days and months with the keyboard and closes without changing filters', async () => {
    mockOrders()
    renderOrders('/orders?dateMin=29%2F02%2F2024')
    await screen.findByText('PED-001')
    const input = screen.getByLabelText('Desde')
    fireEvent.click(input)
    fireEvent.keyDown(screen.getByRole('button', { name: /29 de febrero de 2024/ }), { key: 'ArrowRight' })
    expect(screen.getByText('Marzo de 2024')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /, 1 de marzo de 2024/ })).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('button', { name: /, 1 de marzo de 2024/ }), { key: 'PageUp' })
    expect(screen.getByRole('button', { name: /, 1 de febrero de 2024/ })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Mes siguiente' }))
    expect(screen.getByText('Marzo de 2024')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Mes anterior' }))
    expect(screen.getByText('Febrero de 2024')).toBeInTheDocument()
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: false, cancelable: true }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(input).toHaveValue('29/02/2024')
    expect(input).toHaveFocus()
  })

  it('selects today using the Argentine day and clears from the calendar', async () => {
    vi.setSystemTime(new Date('2026-09-30T02:00:00Z'))
    mockOrders()
    renderOrders('/orders?dateMin=01%2F09%2F2026')
    await screen.findByText('PED-001')
    fireEvent.click(screen.getByRole('button', { name: 'Abrir calendario: Desde' }))
    fireEvent.click(screen.getByRole('button', { name: 'Hoy' }))
    expect(screen.getByLabelText('Desde')).toHaveValue('29/09/2026')
    expect(screen.getByRole('button', { name: 'Abrir calendario: Desde' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Abrir calendario: Desde' }))
    fireEvent.click(screen.getByRole('button', { name: 'Limpiar' }))
    expect(screen.getByLabelText('Desde')).toHaveValue('')
  })

  it('restores date filters from the URL and keeps them while paginating', async () => {
    const fetchMock = mockOrders()
    renderOrders('/orders?dateMin=01%2F09%2F2026&dateMax=30%2F09%2F2026&status=CONFIRMED&search=Norte')
    await screen.findByText('PED-001')
    expect(screen.getByLabelText('Desde')).toHaveValue('01/09/2026')
    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=1&size=20&search=Norte&status=CONFIRMED&dateMin=2026-09-01&dateMax=2026-09-30', expect.anything()))
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '15/09/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=Norte&status=CONFIRMED&dateMin=2026-09-15&dateMax=2026-09-30', expect.anything()))
    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '25/09/2026' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=2026-09-15&dateMax=2026-09-25'), expect.anything()))
  })

  it('allows clearing either boundary and both dates to show all dates', async () => {
    const fetchMock = mockOrders()
    renderOrders()
    await screen.findByText('PED-001')
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=&dateMax=2026-09-30'), expect.anything()))
    fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=&dateMax='), expect.anything()))
    fireEvent.change(screen.getByLabelText('Buscar pedidos'), { target: { value: 'Norte' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=Norte&status=&dateMin=&dateMax=', expect.anything()))
    expect(screen.getByLabelText('Desde')).toHaveValue('')
  })

  it('shows historical orders after clearing filters from an empty result', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const params = new URL(String(input), 'http://localhost').searchParams
      const filtered = Boolean(params.get('dateMin') || params.get('dateMax') || params.get('search') || params.get('status'))
      return response({ content: filtered ? [] : [{ id: 'old-order', number: 'PED-ANTERIOR', customer: 'Almacén Norte', seller: 'Lucía', total: 300, status: 'CONFIRMED', date: '2026-09-19T12:00:00Z' }], page: 0, size: 20, totalElements: filtered ? 0 : 1, totalPages: filtered ? 0 : 1 })
    })
    renderOrders('/orders?page=2&search=Norte&status=DELIVERED')
    expect(await screen.findByText('No hay pedidos para estos filtros')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Ver todos los pedidos' }))
    expect(await screen.findByText('PED-ANTERIOR')).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('')
    expect(screen.getByLabelText('Hasta')).toHaveValue('')
    expect(screen.getByLabelText('Buscar pedidos')).toHaveValue('')
    expect(screen.getByLabelText('Filtrar por estado')).toHaveValue('')
    expect(fetchMock).toHaveBeenCalledWith('/api/orders?page=0&size=20&search=&status=&dateMin=&dateMax=', expect.anything())
  })

  it('offers creation rather than clearing filters when no orders exist without filters', async () => {
    vi.spyOn(global, 'fetch').mockImplementation(() => response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }))
    renderOrders('/orders?dateMin=&dateMax=')
    expect(await screen.findByText('No hay pedidos para mostrar')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ver todos los pedidos' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: '+ Nuevo pedido' })).toHaveLength(2)
  })

  it.each(['31/09/2026', '29/02/2026', '30/9/2026', '2026-09-30', '30/09/0000', 'texto'])('rejects invalid date %s without fetching orders', async (value) => {
    const fetchMock = mockOrders()
    renderOrders()
    await screen.findByText('PED-001')
    fetchMock.mockClear()
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value } })
    expect(screen.getByRole('alert')).toHaveTextContent('Las fechas deben ser válidas y tener formato dd/mm/aaaa.')
    expect(screen.getByLabelText('Desde')).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.queryByText('PED-001')).not.toBeInTheDocument()
  })

  it('rejects reversed ranges and accepts leap days', async () => {
    const fetchMock = mockOrders()
    renderOrders('/orders?dateMin=01%2F10%2F2026&dateMax=30%2F09%2F2026')
    expect(screen.getByRole('alert')).toHaveTextContent('La fecha mínima no puede ser posterior a la fecha máxima.')
    expect(fetchMock).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '29/02/2024' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('dateMin=2024-02-29&dateMax=2026-09-30'), expect.anything()))
  })
})
