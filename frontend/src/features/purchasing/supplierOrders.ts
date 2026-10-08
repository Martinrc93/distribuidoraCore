export type Supplier = { id: string; name: string }
export type PurchaseProduct = { id: string; name: string; cost: number; status: string }
export type SupplierOrder = { id: string; number: string; supplierId: string; supplier: string; date: string; total: number }
export type SupplierOrderItem = { productId: string; productName: string; quantity: number; unitCost: number; lineTotal: number; status: string; currentCost: number }
export type SupplierOrderDetail = { order: SupplierOrder; items: SupplierOrderItem[] }
export type PreviousSupplierOrder = Partial<SupplierOrderDetail> & { available: boolean }
export const supplierOrdersKey = ['supplier-orders']
export const suppliersOptionsKey = ['/api/suppliers', 'purchase-options']
export const purchaseProductsKey = ['/api/products', 'purchase-options']
export const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 }).format(value)
export const decimal = (value: string) => /^\d+(?:[.,]\d{1,4})?$/.test(value.trim()) ? Number(value.trim().replace(',', '.')) : NaN
