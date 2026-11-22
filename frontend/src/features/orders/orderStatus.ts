const labels: Record<string, string> = {
  CONFIRMED: 'Confirmado',
  DELIVERED: 'Entregado',
  CANCELLED: 'Cancelado',
}

export const orderStatusLabel = (status: string) => labels[status] ?? status
