import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '../../shared/components/Button'

const storageKey = 'distribuidora.orders.navigation'

/** Orders visible in the list (current page and filters), so the detail can move between them. */
export type OrderNavigation = { ids: string[]; numbers: string[]; returnTo: string }

export function saveOrderNavigation(navigation: OrderNavigation) {
  try {
    sessionStorage.setItem(storageKey, JSON.stringify(navigation))
  } catch { /* Navigation between orders is optional when storage is unavailable. */ }
}

export function readOrderNavigation(): OrderNavigation | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as Partial<OrderNavigation> | null
    if (!value || !Array.isArray(value.ids) || !Array.isArray(value.numbers) || typeof value.returnTo !== 'string') return null
    return { ids: value.ids.map(String), numbers: value.numbers.map(String), returnTo: value.returnTo.startsWith('/orders') ? value.returnTo : '/orders' }
  } catch {
    return null
  }
}

function typingTarget(target: EventTarget | null) {
  return target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'SELECT', 'TEXTAREA'].includes(target.tagName))
}

/**
 * Previous/next order buttons for the detail page, following the list the user came from.
 * ← and → move between orders unless the user is typing or a dialog is open.
 */
export function OrderNavigator({ orderId }: { orderId: string }) {
  const navigate = useNavigate()
  const navigation = readOrderNavigation()
  const index = navigation ? navigation.ids.indexOf(orderId) : -1
  const previous = navigation && index > 0 ? { id: navigation.ids[index - 1], number: navigation.numbers[index - 1] } : null
  const next = navigation && index >= 0 && index < navigation.ids.length - 1 ? { id: navigation.ids[index + 1], number: navigation.numbers[index + 1] } : null

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || typingTarget(event.target)) return
      if (document.querySelector('dialog[open], [role="dialog"]')) return
      if (event.key === 'ArrowLeft' && previous) { event.preventDefault(); navigate(`/orders/${previous.id}`) }
      if (event.key === 'ArrowRight' && next) { event.preventDefault(); navigate(`/orders/${next.id}`) }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [navigate, previous?.id, next?.id])

  if (index < 0) return null
  return <nav className="order-navigator" aria-label="Navegar entre pedidos">
    <Button type="button" variant="secondary" disabled={!previous} onClick={() => previous && navigate(`/orders/${previous.id}`)} aria-label={previous ? `Pedido anterior ${previous.number}` : 'No hay pedido anterior'} title="Flecha izquierda">← Anterior</Button>
    <span className="order-navigator-position">{index + 1} de {navigation!.ids.length}</span>
    <Button type="button" variant="secondary" disabled={!next} onClick={() => next && navigate(`/orders/${next.id}`)} aria-label={next ? `Pedido siguiente ${next.number}` : 'No hay pedido siguiente'} title="Flecha derecha">Siguiente →</Button>
  </nav>
}
