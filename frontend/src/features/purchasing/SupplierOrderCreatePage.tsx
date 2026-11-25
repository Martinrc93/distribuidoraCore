import { useLocation, useNavigate } from 'react-router-dom'
import { PageHeader } from '../../shared/components/PageHeader'
import { SupplierOrderForm } from './SupplierOrderForm'
import type { SupplierOrderResult } from './supplierOrders'

export default function SupplierOrderCreatePage() {
  const { search } = useLocation()
  const navigate = useNavigate()

  function handleSaved(result: SupplierOrderResult) {
    const filters = new URLSearchParams(search)
    filters.delete('page')
    const query = filters.toString()
    navigate({ pathname: '/supplier-orders', search: query ? `?${query}` : '' }, { replace: true, state: { savedPurchase: result } })
  }

  return <>
    <PageHeader title="Nueva compra" />
    <SupplierOrderForm onClose={() => navigate(`/supplier-orders${search}`, { replace: true })} onSaved={handleSaved} />
  </>
}
