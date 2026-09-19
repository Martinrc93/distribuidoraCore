export function StatCard({ label, value, detail, emphasis = false }: { label: string; value: string; detail: string; emphasis?: boolean }) {
  return <article className={`stat-card${emphasis ? ' stat-card-emphasis' : ''}`}><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>
}
