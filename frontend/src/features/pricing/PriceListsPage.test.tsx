import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PriceListsPage from './PriceListsPage'
import { selectEntity } from '../../test/selectEntity'

function token(authorities: string[]) {
  return `header.${btoa(JSON.stringify({ authorities }))}.signature`
}

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

const lists = { content: [
  { id: 'list-1', code: 'MAYORISTA', name: 'Mayorista', status: 'ACTIVE', isDefault: true },
  { id: 'list-2', code: 'MINORISTA', name: 'Minorista', status: 'INACTIVE', isDefault: false },
], page: 0, size: 20, totalElements: 2, totalPages: 1 }
const prices = { content: [{ priceListId: 'list-1', productId: 'product-1', name: 'Harina', price: 150.5, effectiveOn: '2026-09-24' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
function pageHistory(effectiveOn: string) {
  return { content: [{ effectiveOn, price: 150.5, recordedAt: '2026-09-24T12:00:00Z', updatedAt: '2026-09-24T12:00:00Z', scheduled: effectiveOn > '2026-09-24' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }
}

function renderPage(authorities = ['ADMIN_ALL'], initialEntries = ['/price-lists']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  sessionStorage.setItem('distribuidora.accessToken', token(authorities))
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={initialEntries}><PriceListsPage /></MemoryRouter></QueryClientProvider>)
  return queryClient
}

describe('PriceListsPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); localStorage.removeItem('distribuidora.user-preferences.session.priceLists') })
  beforeEach(() => { vi.restoreAllMocks() })

  it('shows the only list even when saved preferences would hide it', async () => {
    localStorage.setItem('distribuidora.user-preferences.session.priceLists', JSON.stringify({ visibility: 'INACTIVE', expanded: false }))
    const general = { id: 'general', code: 'GENERAL', name: 'Lista general', status: 'ACTIVE', isDefault: true }
    const onlyList = { ...lists, content: [general], totalElements: 1 }
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(onlyList)
      return response({ ...prices, content: [], totalElements: 0, totalPages: 0 })
    })
    renderPage()

    expect(await screen.findByRole('tab', { name: /lista general/i, selected: true })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Renombrar Lista general' })).toBeVisible()
    expect(screen.queryByRole('combobox', { name: 'Mostrar listas' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /plegar listas/i })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Esta lista todavía no tiene precios' })).toBeVisible()
  })

  it('creates a named price list after ten existing lists and refreshes the list query', async () => {
    const user = userEvent.setup()
    const extraLists = Array.from({ length: 8 }, (_, index) => ({ id: `extra-${index}`, code: `EXTRA_${index}`, name: `Lista extra ${index}`, status: 'INACTIVE', isDefault: false }))
    const existingLists = { ...lists, content: [...lists.content, ...extraLists], totalElements: 10 }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20' && !init?.method) return response(existingLists)
      if (String(input) === '/api/pricing/lists' && init?.method === 'POST') return response({ id: 'list-3' }, 201)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await screen.findByRole('tab', { name: /lista extra 7/i })
    await user.click(screen.getByRole('button', { name: /nueva lista/i }))
    expect(screen.queryByRole('tab', { name: 'Reglas de descuento' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/discount-rules'))).toBe(false)
    expect(screen.queryByLabelText('Código', { exact: true })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Nombre de lista'), 'Promoción')
    await user.click(screen.getByRole('button', { name: /guardar lista/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'Promoción' }),
    })))
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
    expect(await screen.findByText('Lista creada correctamente.')).toBeInTheDocument()
  })

  it('edits a selected product price using the list-specific endpoint', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1' && init?.method === 'PUT') return response({}, 204)
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /programar precio de harina/i }))
    await user.clear(screen.getByLabelText('Precio de Harina'))
    await user.type(screen.getByLabelText('Precio de Harina'), '175.25')
    await user.click(screen.getByRole('button', { name: /guardar precio/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ price: 175.25 }),
    })))
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ predicate: expect.any(Function) }))
  })

  it('schedules a product price for its effective date', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1' && init?.method === 'PUT') return response({}, 204)
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /programar precio de harina/i }))
    await user.clear(screen.getByLabelText('Precio de Harina'))
    await user.type(screen.getByLabelText('Precio de Harina'), '180')
    fireEvent.change(screen.getByLabelText('Fecha de vigencia'), { target: { value: '01/10/2026' } })
    await user.click(screen.getByRole('button', { name: /guardar precio/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ price: 180, effectiveOn: '2026-10-01' }),
    })))
  })

  it('shows price history and confirms cancellation of a scheduled entry', async () => {
    const user = userEvent.setup()
    let cancelled = false
    const history = { content: [
      { effectiveOn: '2026-09-24', price: 150.5, recordedAt: '2026-09-24T12:00:00Z', updatedAt: '2026-09-24T12:00:00Z', scheduled: false },
      { effectiveOn: '2026-10-01', price: 180, recordedAt: '2026-09-24T13:00:00Z', updatedAt: '2026-09-24T13:00:00Z', scheduled: true },
    ], page: 0, size: 20, totalElements: 2, totalPages: 1 }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1/history?page=0&size=20') return response(cancelled ? pageHistory('2026-09-24') : history)
      if (String(input) === '/api/pricing/lists/list-1/products/product-1/history/2026-10-01' && init?.method === 'DELETE') {
        cancelled = true
        return response({}, 204)
      }
      return response({})
    })
    const queryClient = renderPage()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: /historial de precios de harina/i }))
    const historyDialog = within(screen.getByRole('dialog', { name: 'Historial de precios' }))
    expect(await historyDialog.findByText('01/10/2026')).toBeInTheDocument()
    expect(historyDialog.getByText('Programado')).toBeInTheDocument()
    await user.click(historyDialog.getByRole('button', { name: 'Cancelar precio del 01/10/2026' }))
    const confirmation = within(screen.getByRole('dialog', { name: '¿Cancelar el precio programado para 01/10/2026?' }))
    expect(fetchMock).not.toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1/history/2026-10-01', expect.anything())
    await user.click(confirmation.getByRole('button', { name: 'Confirmar cancelación' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1/history/2026-10-01', expect.objectContaining({ method: 'DELETE' })))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['pricing', 'history', '/api/pricing/lists/list-1/products/product-1/history?page=0&size=20'] })
    expect(await historyDialog.findByRole('status')).toHaveTextContent('Precio programado para 01/10/2026 cancelado.')
    expect(historyDialog.queryByRole('button', { name: 'Cancelar precio del 01/10/2026' })).not.toBeInTheDocument()
  })

  it('pages through the selected product price history', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (path === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (path === '/api/pricing/lists/list-1/products/product-1/history?page=0&size=20') return response({ ...pageHistory('2026-09-24'), totalElements: 21, totalPages: 2 })
      if (path === '/api/pricing/lists/list-1/products/product-1/history?page=1&size=20') return response({ ...pageHistory('2026-10-01'), totalElements: 21, totalPages: 2 })
      return response({})
    })
    renderPage(['ORDER_CREATE'])

    await user.click(await screen.findByRole('button', { name: /historial de precios de harina/i }))
    await screen.findByText(/Página 1 de 2/)
    expect(screen.getAllByText('24/09/2026')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Siguiente historial' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1/history?page=1&size=20', expect.anything()))
    const historyDialog = within(screen.getByRole('dialog', { name: 'Historial de precios' }))
    expect(await historyDialog.findByText('01/10/2026')).toBeInTheDocument()
    expect(historyDialog.queryByRole('button', { name: /cancelar precio/i })).not.toBeInTheDocument()
    await user.click(historyDialog.getByRole('button', { name: 'Cerrar historial' }))
    await user.click(screen.getByRole('button', { name: /historial de precios de harina/i }))
    expect(await screen.findByText(/Página 1 de 2/)).toBeInTheDocument()
  })

  it('resets the selected list price page when changing lists', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (path === '/api/pricing/lists/list-1/prices?page=2&size=20') return response({ ...prices, page: 2, totalPages: 3 })
      if (path === '/api/pricing/lists/list-2/prices?page=0&size=20') return response({ ...prices, content: [], page: 0, totalPages: 0 })
      return response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    })
    renderPage(['ADMIN_ALL'], ['/price-lists?pricePage=2&historyPage=4'])

    await screen.findByText('Harina')
    await user.click(screen.getByRole('tab', { name: /minorista/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-2/prices?page=0&size=20', expect.anything()))
  })

  it('confirms list deactivation and hides pricing mutations from sellers', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20' && !init?.method) return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      return response({}, 204)
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /desactivar mayorista/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/pricing/lists/list-1/status', expect.anything())
    await user.click(screen.getByRole('button', { name: 'Desactivar lista' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/status', expect.objectContaining({
      method: 'PATCH', body: JSON.stringify({ status: 'INACTIVE' }),
    })))

    cleanup()
    vi.restoreAllMocks()
    vi.spyOn(global, 'fetch').mockImplementation((input) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      return response({})
    })
    renderPage(['ORDER_CREATE'])
    expect(await screen.findByText('Harina')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nueva lista/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /programar precio/i })).not.toBeInTheDocument()
  })

  it('asks for confirmation before creating a list that takes the default list prices', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20' && !init?.method) return response(lists)
      if (String(input) === '/api/pricing/lists/creation-preview') return response({ productsToCopy: 12, defaultListName: 'Mayorista' })
      if (String(input) === '/api/pricing/lists' && init?.method === 'POST') return response({ id: 'list-3' }, 201)
      return response({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /nueva lista/i }))
    await user.type(screen.getByLabelText('Nombre de lista'), 'Promoción')
    await user.click(screen.getByRole('button', { name: /guardar lista/i }))
    const dialog = await screen.findByRole('dialog', { name: '¿Crear la lista con los precios de Mayorista?' })
    expect(dialog).toHaveTextContent('Ya hay 12 productos cargados')
    expect(fetchMock.mock.calls.some(([input, init]) => String(input) === '/api/pricing/lists' && init?.method === 'POST')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Crear lista' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists', expect.objectContaining({ method: 'POST' })))
    expect(await screen.findByText('Lista creada con 12 productos marcados «Sin revisar».')).toBeInTheDocument()
  })

  it('shows follow states, filters them and confirms pending prices of a non-default list', async () => {
    const user = userEvent.setup()
    const activeLists = { ...lists, content: [lists.content[0], { ...lists.content[1], status: 'ACTIVE', pendingReviewCount: 2 }] }
    const followPrices = { content: [
      { productId: 'product-1', name: 'Harina', price: 150.5, effectiveOn: '2026-09-24', followStatus: 'PENDING' },
      { productId: 'product-2', name: 'Azúcar', price: 90, effectiveOn: '2026-09-24', followStatus: 'CONFIRMED' },
      { productId: 'product-3', name: 'Yerba', price: 80, effectiveOn: '2026-09-24', followStatus: null },
    ], page: 0, size: 20, totalElements: 3, totalPages: 1 }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response(activeLists)
      if (path.startsWith('/api/pricing/lists/list-2/prices')) return response(followPrices)
      if (path === '/api/pricing/lists/list-2/reviews/confirm' && init?.method === 'POST') return response({ confirmed: init.body === '{}' ? 2 : 1 })
      return response(prices)
    })
    renderPage()

    await user.click(await screen.findByRole('tab', { name: /minorista/i }))
    const table = await screen.findByRole('table')
    expect(within(table).getByText('Sin revisar')).toBeInTheDocument()
    expect(within(table).getByText('Sin cambios')).toBeInTheDocument()
    expect(within(table).getByText('Precio propio')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /minorista/i })).toHaveTextContent('2 sin revisar')
    await user.selectOptions(screen.getByLabelText('Filtrar por estado del precio'), 'PENDING')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-2/prices?page=0&size=20&followStatus=PENDING', expect.anything()))

    await user.click(screen.getByRole('button', { name: 'Confirmar precio de Harina sin cambios' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-2/reviews/confirm', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ productIds: ['product-1'] }),
    })))
    expect(await screen.findByText('Precio confirmado sin cambios.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirmar 2 sin revisar' }))
    expect(await screen.findByText('2 precios confirmados sin cambios.')).toBeInTheDocument()
  })

  it('warns once before changing a default price followed by other lists and accepts es-AR amounts', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (path === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (path === '/api/pricing/lists/products/product-1/followers') return response([{ priceListId: 'list-2', code: 'MINORISTA', name: 'Minorista' }])
      if (path === '/api/pricing/lists/list-1/products/product-1' && init?.method === 'PUT') return response({}, 204)
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /programar precio de harina/i }))
    expect(screen.getByLabelText('Precio de Harina')).toHaveValue('150,5')
    await user.clear(screen.getByLabelText('Precio de Harina'))
    await user.type(screen.getByLabelText('Precio de Harina'), '1.250,50')
    await user.click(screen.getByRole('button', { name: /guardar precio/i }))
    const dialog = await screen.findByRole('dialog', { name: 'Este cambio también se aplica en Minorista' })
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Guardar y no volver a avisar' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/products/product-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ price: 1250.5, acknowledgeFollowers: true }),
    })))
    expect(await screen.findByText(/actualizado, también en Minorista\./)).toBeInTheDocument()
  })

  it('filters list prices by category, brand and name and keeps the filters in the URL', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response(lists)
      if (path === '/api/categories') return response([{ id: 'category-1', name: 'Almacén', status: 'ACTIVE' }, { id: 'category-2', name: 'Bebidas', status: 'ACTIVE' }])
      if (path === '/api/brands') return response([{ id: 'brand-1', name: 'Molino Norte', status: 'ACTIVE' }])
      if (path.startsWith('/api/pricing/lists/list-1/prices') && path.includes('categoryId=category-2')) return response({ ...prices, content: [], totalElements: 0 })
      return response(prices)
    })
    renderPage()

    expect(await screen.findByText('Harina')).toBeInTheDocument()
    await selectEntity(user, 'Categoría', 'Bebidas')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/prices?page=0&size=20&categoryId=category-2', expect.anything()))
    expect(await screen.findByText('No hay precios para estos filtros')).toBeInTheDocument()
    await selectEntity(user, 'Marca', 'Molino Norte')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/prices?page=0&size=20&categoryId=category-2&brandId=brand-1', expect.anything()))
    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
    await user.type(screen.getByLabelText('Buscar producto en la lista'), 'har')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-1/prices?page=0&size=20&search=har', expect.anything()))
  })

  it('edits prices in the table, rejects amounts below cost and moves to the next row', async () => {
    const user = userEvent.setup()
    const costPrices = { ...prices, content: [
      { ...prices.content[0], cost: 100 },
      { priceListId: 'list-2', productId: 'product-2', name: 'Azúcar', price: 90, effectiveOn: '2026-09-24', cost: 50 },
    ], totalElements: 2 }
    const activeLists = { ...lists, content: [lists.content[0], { ...lists.content[1], status: 'ACTIVE' }] }
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/pricing/lists?page=0&size=20') return response(activeLists)
      if (path.startsWith('/api/pricing/lists/list-2/prices')) return response(costPrices)
      if (path === '/api/pricing/lists/list-2/products/product-1' && init?.method === 'PUT') return response({}, 204)
      return response(prices)
    })
    renderPage()

    await user.click(await screen.findByRole('tab', { name: /minorista/i }))
    const harina = await screen.findByLabelText('Precio de Harina en la lista')
    expect(harina).toHaveValue('150,50')
    await user.clear(harina)
    await user.type(harina, '90{Enter}')
    expect(await screen.findByText(/no puede ser menor al costo/)).toBeInTheDocument()
    expect(harina).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)

    await user.keyboard('{Escape}')
    expect(harina).toHaveValue('150,50')
    await user.clear(harina)
    await user.type(harina, '1.200,75{Enter}')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/pricing/lists/list-2/products/product-1', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ price: 1200.75 }),
    })))
    await waitFor(() => expect(screen.getByLabelText('Precio de Azúcar en la lista')).toHaveFocus())
    expect(screen.queryByLabelText('Precio de Harina en la lista', { selector: 'input[disabled]' })).not.toBeInTheDocument()
  })

  it('prevents duplicate status confirmation submits and keeps dialog open while request is pending', async () => {
    const user = userEvent.setup()
    let resolvePatch!: () => void
    let patchCalls = 0
    vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/pricing/lists?page=0&size=20' && !init?.method) return response(lists)
      if (String(input) === '/api/pricing/lists/list-1/prices?page=0&size=20') return response(prices)
      if (String(input) === '/api/pricing/lists/list-1/status' && init?.method === 'PATCH') {
        patchCalls += 1
        return new Promise<Response>((resolve) => {
          resolvePatch = () => resolve({ ok: true, status: 204, json: () => Promise.resolve({}) } as Response)
        })
      }
      return response({})
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /desactivar mayorista/i }))
    const dialog = screen.getByRole('dialog', { name: 'Desactivar lista' })
    const confirm = within(dialog).getByRole('button', { name: 'Desactivar lista' })
    const cancel = within(dialog).getByRole('button', { name: 'Cancelar' })
    await user.click(confirm)
    expect(patchCalls).toBe(1)
    expect(within(dialog).getByRole('button', { name: 'Desactivando...' })).toBeDisabled()
    expect(cancel).toBeDisabled()

    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Desactivar lista' })).toBeInTheDocument()

    resolvePatch()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Desactivar lista' })).not.toBeInTheDocument())
  })
})
