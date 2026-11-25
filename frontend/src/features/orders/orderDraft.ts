export type DraftLine = { productId: string; quantity: string; lineDiscountPercent: string; unitPriceOverride: string }
export type CollectionDraft = { method: 'CASH' | 'BANK_TRANSFER'; amount: string; reference: string }

export type OrderDraft = {
  idempotencyKey: string
  fields: {
    orderDate: string
    customerId: string
    sellerId: string
    explicitListId: string
    lines: DraftLine[]
    selectedProductId: string
    selectedProductQuantity: string
    selectedProductDiscount: string
    selectedProductPriceOverride?: string
    orderDiscountPercent: string
    previousBalanceInput: string
    selectedPreviousBalance: number
    orderPayment: CollectionDraft
    debtPayment: CollectionDraft
  }
}

function hasStrings(value: unknown, keys: string[]) {
  return typeof value === 'object' && value !== null && keys.every((key) => typeof (value as Record<string, unknown>)[key] === 'string')
}

function isCollectionDraft(value: CollectionDraft | undefined) {
  return (value?.method === 'CASH' || value?.method === 'BANK_TRANSFER') && hasStrings(value, ['amount', 'reference'])
}

export function readOrderDraft(key: string | undefined): OrderDraft | undefined {
  if (!key) return
  try {
    const draft = JSON.parse(localStorage.getItem(key) ?? 'null') as OrderDraft | null
    const fields = draft?.fields
    if (!fields || typeof draft.idempotencyKey !== 'string' || !draft.idempotencyKey) return
    if (!hasStrings(fields, ['orderDate', 'customerId', 'sellerId', 'explicitListId', 'selectedProductId', 'selectedProductQuantity', 'selectedProductDiscount', 'orderDiscountPercent', 'previousBalanceInput'])) return
    if (fields.selectedProductPriceOverride !== undefined && typeof fields.selectedProductPriceOverride !== 'string') return
    if (!Array.isArray(fields.lines) || !fields.lines.every((line) => hasStrings(line, ['productId', 'quantity', 'lineDiscountPercent', 'unitPriceOverride']))) return
    if (!Number.isFinite(fields.selectedPreviousBalance) || fields.selectedPreviousBalance < 0) return
    if (!isCollectionDraft(fields.orderPayment) || !isCollectionDraft(fields.debtPayment)) return
    return draft
  } catch {
    return undefined
  }
}
