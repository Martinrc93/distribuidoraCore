import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadOrderPrice, loadOrderPrices } from './orderPrice'

describe('order price lookup', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

  it('uses bounded batches and deduplicates products without requesting individual prices', async () => {
    const ids = Array.from({ length: 101 }, (_, index) => `product-${index}`)
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = new URL(String(input), 'http://localhost')
      const batch = url.searchParams.get('productIds')!.split(',')
      return new Response(JSON.stringify(batch.map((productId) => ({ productId, priceListId: 'list', priceListCode: 'GENERAL', unitPrice: 0 }))), { status: 200 })
    })
    const prices = await loadOrderPrices('customer', 'list', [...ids, ids[0]], new AbortController().signal)
    expect(prices).toHaveLength(101)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    for (const [path] of fetchMock.mock.calls) {
      expect(String(path)).toContain('/api/pricing/resolve-batch?')
      expect(new URL(String(path), 'http://localhost').searchParams.get('productIds')!.split(',').length).toBeLessThanOrEqual(100)
    }
  })

  it('cancels a pending preload when the customer or list changes', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise<Response>(() => {}))
    const controller = new AbortController()
    const pending = loadOrderPrices('customer', 'list', ['product'], controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await rejected
    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true)
  })

  it('ends a stuck request after 15 seconds and aborts its fetch', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise<Response>(() => {}))
    const pending = loadOrderPrice('customer', 'list', 'product', new AbortController().signal)
    const rejected = expect(pending).rejects.toThrow('La consulta de precio tardó demasiado.')
    await vi.advanceTimersByTimeAsync(15_000)
    await rejected
    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('bounds waiting for the JSON body as well as the HTTP response', async () => {
    vi.useFakeTimers()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, status: 200, json: () => new Promise(() => {}) } as Response)
    const pending = loadOrderPrice('customer', 'list', 'product', new AbortController().signal)
    const rejected = expect(pending).rejects.toThrow('La consulta de precio tardó demasiado.')
    await vi.advanceTimersByTimeAsync(15_000)
    await rejected
  })

  it('aborts a lookup when the selected customer, product or list changes', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise<Response>(() => {}))
    const controller = new AbortController()
    const pending = loadOrderPrice('customer', 'list', 'product', controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await rejected
    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('allows retrying after a timeout and accepts a zero price', async () => {
    vi.useFakeTimers()
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => new Promise<Response>(() => {}))
      .mockResolvedValue(new Response(JSON.stringify({ productId: 'product', priceListId: 'list', priceListCode: 'GENERAL', unitPrice: 0 }), { status: 200 }))
    const pending = loadOrderPrice('customer', 'list', 'product', new AbortController().signal)
    const rejected = expect(pending).rejects.toThrow('La consulta de precio tardó demasiado.')
    await vi.advanceTimersByTimeAsync(15_000)
    await rejected
    await expect(loadOrderPrice('customer', 'list', 'product', new AbortController().signal)).resolves.toMatchObject({ unitPrice: 0 })
    expect(vi.getTimerCount()).toBe(0)
  })
})
