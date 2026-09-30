import { apiGet, type ApiPage } from './client'

export async function apiGetAllPages<T>(path: string): Promise<ApiPage<T>> {
  const first = await apiGet<ApiPage<T>>(path)
  const content = [...first.content]
  const url = new URL(path, 'http://localhost')
  for (let page = 1; page < first.totalPages; page += 1) {
    url.searchParams.set('page', String(page))
    const next = await apiGet<ApiPage<T>>(`${url.pathname}${url.search}`)
    content.push(...next.content)
  }
  return { ...first, content }
}
