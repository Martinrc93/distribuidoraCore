import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiGet, apiPatch, apiPost, apiPut, login } from './client'

describe('HTTP mutation helpers', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it.each([
    ['POST', apiPost],
    ['PUT', apiPut],
    ['PATCH', apiPatch],
  ])('sends a JSON %s request with the bearer token', async (method, request) => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-token')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 'saved' }), { status: 200 }),
    )

    await request<{ id: string }>('/api/resource', { name: 'Example' })

    expect(fetchMock).toHaveBeenCalledWith('/api/resource', {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer access-token',
      },
      body: JSON.stringify({ name: 'Example' }),
    })
  })

  it('returns undefined for a 204 response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }))

    await expect(apiPost('/api/resource', {})).resolves.toBeUndefined()
  })

  it('exposes the API detail for a conflict response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ detail: 'El registro ya existe' }), { status: 409 }),
    )

    await expect(apiPut('/api/resource/1', {})).rejects.toThrow('El registro ya existe')
  })

  it('stores the rotating refresh token returned at login', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      accessToken: 'access-1', tokenType: 'Bearer', expiresInSeconds: 300, refreshToken: 'refresh-1',
    }), { status: 200 }))

    await login('user@example.com', 'password')

    expect(sessionStorage.getItem('distribuidora.accessToken')).toBe('access-1')
    expect(sessionStorage.getItem('distribuidora.refreshToken')).toBe('refresh-1')
  })

  it('refreshes once after a 401 and retries with the rotated access token', async () => {
    sessionStorage.setItem('distribuidora.accessToken', 'expired-access')
    sessionStorage.setItem('distribuidora.refreshToken', 'refresh-1')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (String(input) === '/api/auth/refresh') {
        return new Response(JSON.stringify({
          accessToken: 'access-2', tokenType: 'Bearer', expiresInSeconds: 300, refreshToken: 'refresh-2',
        }), { status: 200 })
      }
      if ((init?.headers as Record<string, string>).Authorization === 'Bearer expired-access') {
        return new Response(JSON.stringify({ detail: 'expired' }), { status: 401 })
      }
      return new Response(JSON.stringify({ id: 'loaded' }), { status: 200 })
    })

    await expect(apiGet('/api/resource')).resolves.toEqual({ id: 'loaded' })

    expect(fetchMock.mock.calls.filter(([input]) => input === '/api/auth/refresh')).toHaveLength(1)
    expect(sessionStorage.getItem('distribuidora.accessToken')).toBe('access-2')
    expect(sessionStorage.getItem('distribuidora.refreshToken')).toBe('refresh-2')
  })

  it('shares a single token rotation between concurrent requests', async () => {
    sessionStorage.setItem('distribuidora.accessToken', 'expired-access')
    sessionStorage.setItem('distribuidora.refreshToken', 'refresh-1')
    let refreshRequests = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (String(input) === '/api/auth/refresh') {
        refreshRequests += 1
        await Promise.resolve()
        return new Response(JSON.stringify({
          accessToken: 'access-2', tokenType: 'Bearer', expiresInSeconds: 300, refreshToken: 'refresh-2',
        }), { status: 200 })
      }
      if ((init?.headers as Record<string, string>).Authorization === 'Bearer expired-access') {
        return new Response(JSON.stringify({ detail: 'expired' }), { status: 401 })
      }
      return new Response(JSON.stringify({ path: String(input) }), { status: 200 })
    })

    await expect(Promise.all([apiGet('/api/one'), apiGet('/api/two')])).resolves.toEqual([
      { path: '/api/one' }, { path: '/api/two' },
    ])

    expect(refreshRequests).toBe(1)
  })

  it('preserves validation metadata from a product price conflict', async () => {
    const affectedPriceLists = [{ priceListId: 'list-1', code: 'MAYORISTA', currentPrice: 120 }]
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      detail: 'El nuevo costo supera precios de listas activas', code: 'INVALID_PRODUCT_PRICES', affectedPriceLists,
    }), { status: 400 }))

    await expect(apiPut('/api/products/product-1', {})).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_PRODUCT_PRICES',
      affectedPriceLists,
    })
  })

  it('revokes the refresh token at logout and clears both local tokens after a server rejection', async () => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-1')
    sessionStorage.setItem('distribuidora.refreshToken', 'refresh-1')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))
    const client = await import('./client')

    expect(typeof client.logout).toBe('function')
    await client.logout()

    expect(fetchMock).toHaveBeenCalledWith('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: 'refresh-1' }),
    })
    expect(sessionStorage.getItem('distribuidora.accessToken')).toBeNull()
    expect(sessionStorage.getItem('distribuidora.refreshToken')).toBeNull()
  })

  it('downloads authenticated PDF responses with the requested filename', async () => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-1')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(new Blob(['pdf'], { type: 'application/pdf' }), { status: 200 }))
    const createObjectURL = vi.fn(() => 'blob:document')
    const revokeObjectURL = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const createElement = vi.spyOn(document, 'createElement')

    const client = await import('./client')
    expect(typeof client.apiGetBlob).toBe('function')
    await client.apiGetBlob('/api/orders/order-1/documents/a4', 'sale.pdf')

    expect(fetchMock).toHaveBeenCalledWith('/api/orders/order-1/documents/a4', { headers: { Authorization: 'Bearer access-1' } })
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob))
    expect(click).toHaveBeenCalledOnce()
    expect(createElement.mock.results.find((result) => result.value instanceof HTMLAnchorElement)?.value.download).toBe('sale.pdf')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:document')
  })

  it('sends an authenticated DELETE without a JSON request body', async () => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-1')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }))
    const client = await import('./client')
    expect(typeof client.apiDelete).toBe('function')

    await client.apiDelete('/api/brands/brand-1')

    expect(fetchMock).toHaveBeenCalledWith('/api/brands/brand-1', {
      method: 'DELETE',
      headers: { Authorization: 'Bearer access-1' },
    })
  })
})
