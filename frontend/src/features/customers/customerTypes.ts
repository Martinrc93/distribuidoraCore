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
  /** RESELLER: its account owes the orders of its associated customers. */
  kind?: 'REGULAR' | 'RESELLER' | string
  resellerId?: string | null
  reseller?: string | null
  associatedCustomersCount?: number
}
