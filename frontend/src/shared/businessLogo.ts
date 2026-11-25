import { apiGet } from './api/client'
import { queryKeys } from './api/queryKeys'

export type BusinessLogo = { content: string | null }
export const businessLogoEndpoint = '/api/settings/business-logo'

export const businessLogoQuery = {
  queryKey: queryKeys.settings.businessLogo,
  queryFn: ({ signal }: { signal: AbortSignal }) => apiGet<BusinessLogo>(businessLogoEndpoint, signal),
  staleTime: 60_000,
  retry: false,
}

export function businessLogoSrc(content: string | null | undefined) {
  return typeof content === 'string' && content ? `data:image/png;base64,${content}` : undefined
}
