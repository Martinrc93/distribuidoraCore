import { ApiError } from '../../shared/api/client'
import { instanceStorageKey } from '../../shared/appBase'
import { getCurrentUserEmail, getCurrentUserId } from '../../shared/auth/permissions'

export type PriceList = { id: string; code: string; name: string; status: string; isDefault: boolean; pendingReviewCount?: number }
/** `followStatus`: PENDING (copied from the default list, not reviewed), CONFIRMED (reviewed, still follows it) or null (own price). */
export type ProductPrice = { productId: string; name: string; price: number; effectiveOn?: string; followStatus?: 'PENDING' | 'CONFIRMED' | null; cost?: number | null }
export type FollowFilter = '' | 'PENDING' | 'FOLLOWING' | 'OWN'
export type CreationPreview = { productsToCopy: number; defaultListName?: string | null }
export type Follower = { priceListId: string; code: string; name: string }
export type CatalogOption = { id: string; name: string; status: string }
export type FollowerWarning = { followers: Follower[]; amount: number; effectiveOn: string; product: ProductPrice }
export type HistorySelection = { productId: string; productName: string; listId: string; listName: string }
export type PriceHistory = { effectiveOn: string; price: number; recordedAt: string; updatedAt: string; scheduled: boolean }
export type ListVisibility = 'ACTIVE' | 'INACTIVE' | 'ALL'
export type PriceListPreferences = { visibility: ListVisibility; expanded: boolean }
export type Row = Record<string, string>

const defaultPriceListPreferences: PriceListPreferences = { visibility: 'ALL', expanded: true }

/** Accepts "1250.5", "1250,50" and "1.250,50" (es-AR); returns NaN for anything else. */
export function parsePriceInput(value: string) {
  const text = value.trim().replace(/\s/g, '')
  if (!/^\d{1,3}(\.\d{3})*(,\d{1,4})?$|^\d+([.,]\d{1,4})?$/.test(text)) return NaN
  if (text.includes(',')) return Number(text.replace(/\./g, '').replace(',', '.'))
  // Without a comma, dots grouping exactly three digits are thousands separators ("1.250" = 1250).
  return Number(/^\d{1,3}(\.\d{3})+$/.test(text) ? text.replace(/\./g, '') : text)
}

export function followerNames(followers: Follower[]) {
  const names = followers.map((follower) => follower.name)
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} y ${names.at(-1)}`
}

export function priceListPreferenceKey() {
  const userId = getCurrentUserId() ?? getCurrentUserEmail() ?? 'session'
  return instanceStorageKey(`distribuidora.user-preferences.${encodeURIComponent(userId)}.priceLists`)
}

export function readPriceListPreferences(key: string): PriceListPreferences {
  try {
    const saved = localStorage.getItem(key)
    if (!saved) return defaultPriceListPreferences
    const value = JSON.parse(saved) as Partial<PriceListPreferences>
    return {
      visibility: value.visibility === 'ACTIVE' || value.visibility === 'INACTIVE' || value.visibility === 'ALL' ? value.visibility : 'ALL',
      expanded: typeof value.expanded === 'boolean' ? value.expanded : true,
    }
  } catch {
    return defaultPriceListPreferences
  }
}

export function formatEffectiveDate(value: string | null | undefined) {
  if (!value) return '—'
  const [year, month, day] = value.split('-')
  return day && month && year ? [day, month, year].join('/') : value
}

export function readableError(cause: unknown, fallback: string) {
  if (cause instanceof ApiError) {
    if (cause.status === 403) return 'No tenés permisos para modificar listas de precios.'
    if (cause.status === 404) return 'La lista o el precio ya no está disponible.'
    return cause.detail || fallback
  }
  return cause instanceof Error ? cause.message : fallback
}
