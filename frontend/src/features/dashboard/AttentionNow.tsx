import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../shared/api/queryKeys'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { formatBusinessDate, todayInArgentina } from '../../shared/dates'
import { formatNumber } from '../../shared/format'
import { Button } from '../../shared/components/Button'
import { parseDashboardReport } from './dashboardResponses'
import { pendingReviewsPath } from './PendingOrderReviews'

type Tone = 'calm' | 'warning' | 'danger'

function AttentionCard({ title, value, detail, tone, href, linkLabel, loading, failed, onRetry }: {
  title: string; value?: number; detail: string; tone: Tone; href: string; linkLabel: string; loading: boolean; failed: boolean; onRetry: () => void
}) {
  return <article className={`attention-card attention-card-${tone}`}>
    <h3>{title}</h3>
    {loading ? <p className="attention-value" role="status">Consultando…</p>
      : failed ? <p className="attention-detail">No se pudo consultar. <Button type="button" variant="link" onClick={onRetry}>Volver a consultar</Button></p>
      : <><p className="attention-value">{formatNumber(value ?? 0, 0)}</p><p className="attention-detail">{detail}</p></>}
    <Button variant="link" href={href}>{linkLabel}</Button>
  </article>
}

/** Current pending work for the administrator; it does not depend on the summary's period. */
export function AttentionNow() {
  const [today] = useState(() => todayInArgentina())
  const reviews = useQuery({ queryKey: queryKeys.dashboard.orderReviews, queryFn: ({ signal }) => apiGet<ApiPage<unknown>>(pendingReviewsPath, signal), retry: false })
  const current = useQuery({
    queryKey: queryKeys.dashboard.analytics(today, today),
    queryFn: async ({ signal }) => parseDashboardReport(await apiGet<unknown>(`/api/dashboard/analytics?dateMin=${today}&dateMax=${today}`, signal)),
    retry: false,
  })
  const reviewCount = reviews.data?.totalElements
  const pending = current.data?.current

  return <section className="attention-now" aria-labelledby="attention-now-title">
    <div className="attention-now-heading"><h2 id="attention-now-title">Para atender ahora</h2><p>Situación actual; no depende del período.</p></div>
    <div className="attention-grid">
      <AttentionCard title="Pedidos de vendedores por revisar" value={reviewCount} tone={reviewCount ? 'warning' : 'calm'}
        detail={reviewCount ? 'Incluye pendientes de todas las fechas.' : 'Todo revisado.'}
        href="/orders?review=PENDING&dateMin=&dateMax=" linkLabel="Ver revisiones"
        loading={reviews.isLoading} failed={reviews.isError} onRetry={() => void reviews.refetch()} />
      <AttentionCard title="Pedidos por entregar" value={pending?.pendingOrders} tone={pending?.pendingOrders ? 'warning' : 'calm'}
        detail={pending?.oldestPendingAt ? `El más antiguo es del ${formatBusinessDate(pending.oldestPendingAt)}.` : 'Sin pedidos pendientes de entrega.'}
        href="/orders?status=CONFIRMED&dateMin=&dateMax=" linkLabel="Ver pedidos por entregar"
        loading={current.isLoading} failed={current.isError} onRetry={() => void current.refetch()} />
      <AttentionCard title="Stock crítico" value={pending?.stockAlertCount} tone={pending?.stockAlertCount ? 'danger' : 'calm'}
        detail={pending?.stockAlertCount ? 'Productos sin stock o con saldo negativo.' : 'Ningún producto activo sin stock.'}
        href="/products" linkLabel="Ver stock"
        loading={current.isLoading} failed={current.isError} onRetry={() => void current.refetch()} />
    </div>
  </section>
}
