import { apiGet, type ApiPage } from './client'

const MAX_CONCURRENT_PAGES = 4

export async function apiGetAllPages<T>(path: string, signal?: AbortSignal): Promise<ApiPage<T>> {
  const first = await apiGet<ApiPage<T>>(path, signal)
  const content = [...first.content]
  const url = new URL(path, 'http://localhost')
  for (let start = (first.page ?? 0) + 1; start < first.totalPages; start += MAX_CONCURRENT_PAGES) {
    signal?.throwIfAborted()
    const size = Math.min(MAX_CONCURRENT_PAGES, first.totalPages - start)
    const pages = await Promise.all(Array.from({ length: size }, (_, index) => {
      url.searchParams.set('page', String(start + index))
      return apiGet<ApiPage<T>>(`${url.pathname}${url.search}`, signal)
    }))
    for (const next of pages) content.push(...next.content)
  }
  return { ...first, content }
}
