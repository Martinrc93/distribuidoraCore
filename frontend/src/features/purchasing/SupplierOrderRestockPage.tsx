import { useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { Button } from '../../shared/components/Button'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { SupplierOrderForm } from './SupplierOrderForm'
import { purchaseSavedMessage, type SupplierOrderResult } from './supplierOrders'

export default function SupplierOrderRestockPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [saved, setSaved] = useState<SupplierOrderResult>()
  const productId = params.get('productId')
  const fromDashboard = params.get('source') === 'analytics'
  const sourceName = fromDashboard ? 'Dashboard' : 'Stock'
  const filters = new URLSearchParams(params.get('filters') ?? '').toString()
  const returnPath = `${fromDashboard ? '/analytics' : '/products'}${filters ? `?${filters}` : ''}`
  const close = () => navigate(returnPath, { replace: true })

  if (!productId) return <Navigate to="/supplier-orders" replace />

  return <>
    <PageHeader eyebrow="Compras" title="Reposición" />
    {saved ? <Panel title={`Compra ${saved.number}`}>
      <p className="success-text" role="status">Compra {purchaseSavedMessage(saved.status)}</p>
      <div className="page-actions">
        <Button href={`/supplier-orders/${encodeURIComponent(saved.id)}`}>Ver compra</Button>
        <Button type="button" variant="secondary" onClick={close}>Volver a {sourceName}</Button>
      </div>
    </Panel> : <SupplierOrderForm key={productId} restockProductId={productId} onClose={close} onSaved={setSaved} />}
  </>
}
