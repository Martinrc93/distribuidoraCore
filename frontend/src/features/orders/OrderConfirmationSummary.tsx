import { CurrencyText } from '../../shared/components/CurrencyText'
import { Button } from '../../shared/components/Button'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { formatMoney } from '../../shared/format'
import { hasPermission } from '../../shared/auth/permissions'
import { OrderNotificationDialog } from './OrderNotificationDialog'

export type ConfirmationResponse = {
  orderId: string
  saleId: string
  orderNumber: string
  saleNumber: string
  total: number
  paid: number
  balance: number
  previousBalanceAmount?: number
  collectionTotal?: number
  creditLimitWarning?: { creditLimit: number; projectedBalance: number; exceededBy: number } | null
}

type Props = {
  customerName?: string
  customerId?: string
  responseData: ConfirmationResponse
  notifyWhatsApp: boolean
  notificationRequested: boolean
  onOpenNotify: () => void
  onCloseNotify: () => void
  onNotificationRequested: () => void
  onBackToOrders: () => void
}

export function OrderConfirmationSummary({
  customerName,
  customerId,
  responseData,
  notifyWhatsApp,
  notificationRequested,
  onOpenNotify,
  onCloseNotify,
  onNotificationRequested,
  onBackToOrders,
}: Props) {
  return <>
    <div className="order-customer-header">
      <PageHeader eyebrow="Pedido confirmado" title={customerName ?? 'Pedido confirmado'} description={`Pedido ${responseData.orderNumber} · El pedido y la venta quedaron registrados.`} />
    </div>
    <Panel title="Resultado de la confirmación">
      <dl className="confirmation-result">
        <dt>Venta</dt><dd>{responseData.saleNumber}</dd>
        <dt>Total</dt><dd><CurrencyText>{formatMoney(responseData.total, 2)}</CurrencyText></dd>
        <dt>Cobrado</dt><dd><CurrencyText>{formatMoney(responseData.paid, 2)}</CurrencyText></dd>
        <dt>Saldo pendiente</dt><dd><CurrencyText>{formatMoney(responseData.balance, 2)}</CurrencyText></dd>
      </dl>
      {Number(responseData.previousBalanceAmount ?? 0) > 0 && <dl className="confirmation-result">
        <dt>Saldo anterior para entrega</dt><dd><CurrencyText>{formatMoney(responseData.previousBalanceAmount!, 2)}</CurrencyText></dd>
        <dt>Total a cobrar con la entrega</dt><dd><CurrencyText>{formatMoney(responseData.collectionTotal ?? responseData.total + responseData.previousBalanceAmount!, 2)}</CurrencyText></dd>
      </dl>}
      {responseData.creditLimitWarning && <p className="warning-text" role="status">
        El saldo proyectado <CurrencyText>{formatMoney(responseData.creditLimitWarning.projectedBalance, 2)}</CurrencyText> supera el límite de crédito <CurrencyText>{formatMoney(responseData.creditLimitWarning.creditLimit, 2)}</CurrencyText> por <CurrencyText>{formatMoney(responseData.creditLimitWarning.exceededBy, 2)}</CurrencyText>.
      </p>}
      <div className="page-actions">
        <Button href={`/orders/${responseData.orderId}`}>Ver pedido</Button>
        <Button variant="secondary" onClick={onBackToOrders}>Volver a pedidos</Button>
        {hasPermission('ORDER_DOCUMENT_EXPORT') && <DocumentActions
          path={`/api/orders/${responseData.orderId}/documents/a4`}
          filename={`${responseData.orderNumber}-a4.pdf`}
          downloadLabel="Descargar PDF"
        />}
        {hasPermission('ORDER_NOTIFY') && <Button type="button" variant="secondary" onClick={onOpenNotify}>
          <svg className="mr-[8px] shrink-0" width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
            <path d="M20.52 3.48A11.91 11.91 0 0 0 12.04 0C5.46 0 .1 5.35.1 11.94c0 2.1.55 4.16 1.6 5.98L0 24l6.26-1.64a11.95 11.95 0 0 0 5.78 1.47h.01c6.58 0 11.94-5.35 11.94-11.94 0-3.19-1.24-6.18-3.47-8.41ZM12.05 21.8a9.9 9.9 0 0 1-5.05-1.38l-.36-.22-3.72.98.99-3.63-.24-.37a9.9 9.9 0 0 1-1.52-5.24c0-5.47 4.45-9.92 9.93-9.92a9.85 9.85 0 0 1 7.02 2.91A9.87 9.87 0 0 1 22 11.89c0 5.47-4.45 9.92-9.95 9.92Zm5.44-7.43c-.3-.15-1.77-.87-2.04-.97-.27-.1-.47-.15-.67.15-.2.3-.77.97-.94 1.17-.17.2-.35.22-.64.07-.3-.15-1.26-.46-2.4-1.48-.89-.79-1.49-1.77-1.66-2.07-.17-.3-.02-.46.13-.61.13-.13.3-.35.45-.52.15-.18.2-.3.3-.5.1-.2.05-.37-.03-.52-.07-.15-.67-1.62-.92-2.22-.24-.58-.49-.5-.67-.51h-.57c-.2 0-.52.07-.79.37-.27.3-1.04 1.02-1.04 2.49s1.07 2.89 1.22 3.09c.15.2 2.1 3.2 5.09 4.49.71.3 1.27.49 1.7.62.71.23 1.36.2 1.87.12.57-.08 1.77-.72 2.02-1.42.25-.7.25-1.3.17-1.42-.07-.13-.27-.2-.57-.35Z" />
          </svg>
          Enviar por WhatsApp
        </Button>}
      </div>
      {notificationRequested && <p className="success-text mt-[12px]" role="status">Envío por WhatsApp solicitado.</p>}
    </Panel>
    {notifyWhatsApp && <OrderNotificationDialog
      orderId={responseData.orderId} customerId={customerId} channel="WHATSAPP"
      onClose={onCloseNotify}
      onRequested={onNotificationRequested}
    />}
  </>
}
