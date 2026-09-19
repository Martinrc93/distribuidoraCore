import { getAccessToken } from '../api/client'

type JwtPayload = {
  authorities?: unknown
}

export function getAuthorities(): string[] {
  const token = getAccessToken()
  if (!token) return []

  try {
    const payload = token.split('.')[1]
    if (!payload) return []

    const base64 = payload.replaceAll('-', '+').replaceAll('_', '/')
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
    const decoded = JSON.parse(atob(padded)) as JwtPayload
    return Array.isArray(decoded.authorities) && decoded.authorities.every((authority) => typeof authority === 'string')
      ? decoded.authorities
      : []
  } catch {
    return []
  }
}

export function hasAuthority(authority: string): boolean {
  return getAuthorities().includes(authority)
}
