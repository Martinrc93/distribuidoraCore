import { apiGet } from '../../shared/api/client'

export type PriceResolution = { productId: string; priceListId: string; priceListCode: string; unitPrice: number }

async function withPriceDeadline<T>(signal: AbortSignal, load: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  let timeout: ReturnType<typeof setTimeout> | undefined
  let rejectDeadline!: (error: Error) => void
  const deadline = new Promise<never>((_resolve, reject) => { rejectDeadline = reject })
  const cancel = () => {
    controller.abort()
    rejectDeadline(new DOMException('Price lookup cancelled', 'AbortError'))
  }
  signal.addEventListener('abort', cancel, { once: true })
  if (signal.aborted) cancel()
  timeout = setTimeout(() => {
    rejectDeadline(new Error('La consulta de precio tardó demasiado. Vuelve a intentarlo.'))
    controller.abort()
  }, 15_000)
  try {
    return await Promise.race([load(controller.signal), deadline])
  } finally {
    clearTimeout(timeout)
    signal.removeEventListener('abort', cancel)
  }
}

export async function loadOrderPrice(customerId: string, priceListId: string, productId: string, signal: AbortSignal): Promise<PriceResolution> {
  const result = await withPriceDeadline(signal, (requestSignal) => apiGet<PriceResolution>(`/api/pricing/resolve?customerId=${encodeURIComponent(customerId)}&productId=${encodeURIComponent(productId)}&priceListId=${encodeURIComponent(priceListId)}`, requestSignal))
  if (!Number.isFinite(result.unitPrice) || result.unitPrice < 0) throw new Error('La consulta no devolvió un precio válido.')
  return result
}

export async function loadOrderPrices(customerId: string, priceListId: string, productIds: string[], signal: AbortSignal): Promise<PriceResolution[]> {
  return withPriceDeadline(signal, async (requestSignal) => {
    const prices: PriceResolution[] = []
    const ids = [...new Set(productIds)].sort()
    for (let offset = 0; offset < ids.length; offset += 100) {
      const batch = ids.slice(offset, offset + 100)
      const result = await apiGet<PriceResolution[]>(`/api/pricing/resolve-batch?customerId=${encodeURIComponent(customerId)}&priceListId=${encodeURIComponent(priceListId)}&productIds=${batch.map(encodeURIComponent).join(',')}`, requestSignal)
      if (!Array.isArray(result) || result.some((price) => !batch.includes(price.productId) || !Number.isFinite(price.unitPrice) || price.unitPrice < 0)) throw new Error('La consulta no devolvió precios válidos.')
      prices.push(...result)
    }
    return prices
  })
}
