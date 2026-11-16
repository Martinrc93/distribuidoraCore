export type Supplier = { id: string; name: string }
export type PurchaseProduct = { id: string; name: string; cost: number; status: string; stock?: number; suppliers?: Array<{ id: string; name: string }> }
/** LEGACY: registered before purchases moved stock; DRAFT: no stock effect; CONFIRMED: added to stock; CANCELLED: reverted. */
export type SupplierOrderStatus = 'LEGACY' | 'DRAFT' | 'CONFIRMED' | 'CANCELLED'
export type SupplierOrder = { id: string; number: string; supplierId: string; supplier: string; date: string; total: number; status?: SupplierOrderStatus }
export type SupplierOrderItem = { productId: string; productName: string; quantity: number; unitCost: number; lineTotal: number; status: string; currentCost: number }
export type SupplierOrderDetail = { order: SupplierOrder; items: SupplierOrderItem[] }
export type PreviousSupplierOrder = Partial<SupplierOrderDetail> & { available: boolean }
export const supplierOrdersKey = ['supplier-orders']
export const suppliersOptionsKey = ['/api/suppliers', 'purchase-options']
export const purchaseProductsKey = ['/api/products', 'purchase-options']
export const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 }).format(value)
export const decimal = (value: string) => /^\d+(?:[.,]\d{1,4})?$/.test(value.trim()) ? Number(value.trim().replace(',', '.')) : NaN
export const isHalfUnit = (value: number) => Number.isFinite(value) && Number.isInteger(value * 2)
export const purchaseStatusLabel: Record<SupplierOrderStatus, string> = {
  LEGACY: 'Histórica', DRAFT: 'Borrador', CONFIRMED: 'Confirmada', CANCELLED: 'Anulada',
}
export const purchaseStatusTone: Record<SupplierOrderStatus, 'strong' | 'soft' | 'muted'> = {
  LEGACY: 'muted', DRAFT: 'soft', CONFIRMED: 'strong', CANCELLED: 'muted',
}
