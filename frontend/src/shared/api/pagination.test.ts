import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiGetAllPages } from './pagination'

afterEach(() => vi.restoreAllMocks())

describe('complete entity options', () => {
  it('includes later pages while preserving size, scope and filters', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input) => {
      const url = new URL(String(input), 'http://localhost')
      const page = Number(url.searchParams.get('page'))
      return Promise.resolve({ ok: true, json: async () => ({ content: [{ id: `customer-${page}`, name: `Customer ${page}` }], page, size: 20, totalElements: 41, totalPages: 3 }) } as Response)
    })
    const data = await apiGetAllPages<{ id: string }>('/api/customers?page=0&size=20&status=ACTIVE&sellerId=seller-1')
    expect(data.content.map((item) => item.id)).toEqual(['customer-0', 'customer-1', 'customer-2'])
    expect(fetchMock).toHaveBeenCalledWith('/api/customers?page=2&size=20&status=ACTIVE&sellerId=seller-1', expect.anything())
  })

  it('rejects a later page failure rather than offering incomplete options', async () => {
    vi.spyOn(global, 'fetch')
      .mockResolvedValueOnce({ ok: true, json: async () => ({ content: [{ id: 'one' }], totalPages: 2 }) } as Response)
      .mockResolvedValueOnce({ ok: false, status: 503, json: async () => ({ detail: 'Unavailable' }) } as Response)
    await expect(apiGetAllPages('/api/sellers?page=0&size=100')).rejects.toThrow('Unavailable')
  })
})
