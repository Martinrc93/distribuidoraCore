import { useSearchParams } from 'react-router-dom'

export function useUrlListState(filterKeys: string[] = ['search'], pageSize = 20, pageParam = 'page') {
  const [params, setParams] = useSearchParams()
  const rawPage = Number(params.get(pageParam) ?? 0)
  const page = Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 0

  function getFilter(key: string) {
    return params.get(key) ?? ''
  }

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete(pageParam)
    setParams(next, { replace: true })
  }

  function setPage(value: number) {
    const next = new URLSearchParams(params)
    if (value > 0) next.set(pageParam, String(value))
    else next.delete(pageParam)
    setParams(next)
  }

  function filterQuery(keys: string[] = filterKeys) {
    return keys.map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(getFilter(key).trim())}`).join('&')
  }

  return { page, pageSize, getFilter, setFilter, setPage, filterQuery }
}
