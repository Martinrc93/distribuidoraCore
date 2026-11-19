export type Supplier = { id: string; name: string }
export type PurchaseProduct = { id: string; name: string; status: string; cost?: number | null; stock?: number; suppliers?: Array<{ id: string; name: string }> }
/** LEGACY: registered before purchases moved stock; DRAFT: no stock effect; CONFIRMED: added to stock; CANCELLED: reverted. */
export type SupplierOrderStatus = 'LEGACY' | 'DRAFT' | 'CONFIRMED' | 'CANCELLED'
export type SupplierOrder = { id: string; number: string; supplierId: string; supplier: string; date: string; total: number; status?: SupplierOrderStatus }
export type SupplierOrderResult = Pick<SupplierOrder, 'id' | 'number' | 'total' | 'status'>
export type SupplierOrderItem = { productId: string; productName: string; quantity: number; unitCost: number; currentCost?: number | null; status: string }
export type SupplierOrderDetail = { order: SupplierOrder; items: SupplierOrderItem[] }
export type PreviousSupplierOrder = Partial<SupplierOrderDetail> & { available: boolean }
export const supplierOrdersKey = ['supplier-orders']
export const suppliersOptionsKey = ['/api/suppliers', 'purchase-options']
export const purchaseProductsKey = ['/api/products', 'purchase-options']
export const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 }).format(value)
export const decimal = (value: string) => /^\d+(?:[.,]\d{1,4})?$/.test(value.trim()) ? Number(value.trim().replace(',', '.')) : NaN
export const isHalfUnit = (value: number) => Number.isFinite(value) && Number.isInteger(value * 2)

/** Cover the negative balance and leave at least one unit, rounding up to a half unit. */
export function suggestedRestockQuantity(stock: number): string {
  return String(Math.ceil((Math.max(0, -stock) + 1) * 2) / 2).replace('.', ',')
}

export function restockPurchasePath(productId: string, source: 'stock' | 'analytics', filters: string): string {
  const params = new URLSearchParams({ productId, source })
  if (filters) params.set('filters', filters)
  return `/supplier-orders/restock?${params}`
}

export const purchaseStatusLabel: Record<SupplierOrderStatus, string> = {
  LEGACY: 'Histórica', DRAFT: 'Borrador', CONFIRMED: 'Confirmada', CANCELLED: 'Anulada',
}
export const purchaseStatusTone: Record<SupplierOrderStatus, 'strong' | 'soft' | 'muted'> = {
  LEGACY: 'muted', DRAFT: 'soft', CONFIRMED: 'strong', CANCELLED: 'muted',
}
