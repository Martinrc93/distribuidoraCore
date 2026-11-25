import { useSearchParams } from 'react-router-dom'
import { useDebouncedValue } from './useDebouncedValue'

export function useUrlListState(filterKeys: string[] = ['search'], pageSize = 20, pageParam = 'page') {
  const [params, setParams] = useSearchParams()
  const rawPage = Number(params.get(pageParam) ?? 0)
  const page = Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 0
  const search = useDebouncedValue(params.get('search') ?? '')

  function getFilter(key: string) {
    return params.get(key) ?? ''
  }

  function setFilter(key: string, value: string) {
    setParams(current => {
      const next = new URLSearchParams(current)
      if (value) next.set(key, value)
      else next.delete(key)
      next.delete(pageParam)
      return next
    }, { replace: true })
  }

  function setPage(value: number) {
    setParams(current => {
      const next = new URLSearchParams(current)
      if (value > 0) next.set(pageParam, String(value))
      else next.delete(pageParam)
      return next
    })
  }

  function filterQuery(keys: string[] = filterKeys) {
    return keys.map((key) => `${encodeURIComponent(key)}=${encodeURIComponent((key === 'search' ? search : getFilter(key)).trim())}`).join('&')
  }

  return { page, pageSize, getFilter, setFilter, setPage, filterQuery }
}
