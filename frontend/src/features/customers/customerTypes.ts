export type Customer = {
  id: string
  number?: string
  name: string
  cuitId?: string | null
  email?: string | null
  phone?: string | null
  address?: string | null
  zone?: string | null
  seller?: string
  sellerId?: string
  priceListId?: string | null
  balance?: number
  status: 'ACTIVE' | 'INACTIVE' | string
}
