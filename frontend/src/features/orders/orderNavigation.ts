import { instanceStorageKey } from '../../shared/appBase'

const storageKey = instanceStorageKey('distribuidora.orders.navigation')

export function saveOrdersReturnPath(returnTo: string) {
  try {
    sessionStorage.setItem(storageKey, JSON.stringify({ returnTo }))
  } catch { /* The detail uses the default list when storage is unavailable. */ }
}

export function readOrdersReturnPath(): string {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as { returnTo?: unknown } | null
    if (typeof value?.returnTo === 'string' && value.returnTo.startsWith('/orders')) return value.returnTo
  } catch {
    return '/orders'
  }
  return '/orders'
}
