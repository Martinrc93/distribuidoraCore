import { getAccessToken } from '../api/client'

type JwtPayload = {
  authorities?: unknown
  sub?: unknown
}

function getPayload(): JwtPayload | undefined {
  const token = getAccessToken()
  if (!token) return undefined

  try {
    const payload = token.split('.')[1]
    if (!payload) return undefined

    const base64 = payload.replaceAll('-', '+').replaceAll('_', '/')
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
    const decoded = JSON.parse(atob(padded)) as JwtPayload
    return decoded
  } catch {
    return undefined
  }
}

export function getAuthorities(): string[] {
  const authorities = getPayload()?.authorities
  return Array.isArray(authorities) && authorities.every(value => typeof value === 'string') ? authorities : []
}

export function getCurrentUserId(): string | undefined {
  const sub = getPayload()?.sub
  return typeof sub === 'string' ? sub : undefined
}

export function hasAuthority(authority: string): boolean {
  return getAuthorities().includes(authority)
}
