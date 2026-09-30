import { SearchableSelect } from './SearchableSelect'

type EntityOption = { id: string; name: string }
type EntitySelectProps = {
  options: EntityOption[]
  value: string
  onChange: (value: string) => void
  label?: string
  mode?: 'filter' | 'selection'
  emptyLabel?: string
  loading?: boolean
  disabled?: boolean
  required?: boolean
  invalid?: boolean
  describedBy?: string
}

function EntitySelect({ entity, label, mode = 'filter', emptyLabel, ...props }: EntitySelectProps & { entity: 'customer' | 'seller' }) {
  const customer = entity === 'customer'
  return <SearchableSelect {...props}
    label={label ?? (customer ? 'Buscar por cliente' : 'Buscar por vendedor')}
    allLabel={emptyLabel ?? (mode === 'filter' ? customer ? 'Todos los clientes' : 'Todos los vendedores' : customer ? 'Seleccionar cliente...' : 'Seleccionar vendedor...')}
    unavailableLabel={customer ? 'Cliente no disponible' : 'Vendedor no disponible'}
    loadingLabel={customer ? 'Cargando clientes…' : 'Cargando vendedores…'}
    fullWidth={mode === 'selection'} />
}

export function CustomerSelect(props: EntitySelectProps) {
  return <EntitySelect {...props} entity="customer" />
}

export function SellerSelect(props: EntitySelectProps) {
  return <EntitySelect {...props} entity="seller" />
}
