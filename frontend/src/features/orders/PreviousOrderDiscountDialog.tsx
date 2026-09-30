import { useEffect, useRef } from 'react'
import { Button } from '../../shared/components/Button'

type Props = {
  orderNumber: string
  orderDiscount: number
  items: Array<{ productName: string; lineDiscountPercent: number }>
  anchor: HTMLElement
  onChoose: (copyDiscounts: boolean) => void
  onDismiss: () => void
}

export function PreviousOrderDiscountDialog({ orderNumber, orderDiscount, items, anchor, onChoose, onDismiss }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    element.querySelector<HTMLButtonElement>('[data-default-action]')?.focus()
    return () => {
      element.close()
      anchor.focus({ preventScroll: true })
    }
  }, [anchor])

  return <dialog ref={dialog} className="order-discount-dialog" aria-modal="true" aria-labelledby="previous-discount-title" aria-describedby="previous-discount-description" onCancel={(event) => { event.preventDefault(); onDismiss() }}>
    <h2 id="previous-discount-title">El pedido anterior tiene descuentos</h2>
    <p id="previous-discount-description">El pedido {orderNumber} incluye descuentos. ¿Querés copiarlos al nuevo pedido?</p>
    <ul>
      {orderDiscount > 0 && <li>Descuento general: {orderDiscount}%</li>}
      {items.filter((item) => item.lineDiscountPercent > 0).map((item, index) => <li key={index}>{item.productName}: {item.lineDiscountPercent}%</li>)}
    </ul>
    <div className="page-actions">
      <Button type="button" variant="secondary" onClick={onDismiss}>Cancelar</Button>
      <Button data-default-action type="button" variant="secondary" onClick={() => onChoose(false)}>Cargar sin descuentos</Button>
      <Button type="button" onClick={() => onChoose(true)}>Copiar descuentos</Button>
    </div>
  </dialog>
}
