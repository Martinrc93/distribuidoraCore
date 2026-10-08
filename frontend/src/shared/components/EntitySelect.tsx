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

const entityLabels = {
  customer: { filter: 'Buscar por cliente', all: 'Todos los clientes', select: 'Seleccionar cliente...', unavailable: 'Cliente no disponible', loading: 'Cargando clientes…' },
  seller: { filter: 'Buscar por vendedor', all: 'Todos los vendedores', select: 'Seleccionar vendedor...', unavailable: 'Vendedor no disponible', loading: 'Cargando vendedores…' },
  supplier: { filter: 'Buscar por proveedor', all: 'Todos los proveedores', select: 'Seleccionar proveedor...', unavailable: 'Proveedor no disponible', loading: 'Cargando proveedores…' },
}

function EntitySelect({ entity, label, mode = 'filter', emptyLabel, ...props }: EntitySelectProps & { entity: keyof typeof entityLabels }) {
  const labels = entityLabels[entity]
  return <SearchableSelect {...props}
    label={label ?? labels.filter}
    allLabel={emptyLabel ?? (mode === 'filter' ? labels.all : labels.select)}
    unavailableLabel={labels.unavailable}
    loadingLabel={labels.loading}
    fullWidth={mode === 'selection'} />
}

export function CustomerSelect(props: EntitySelectProps) {
  return <EntitySelect {...props} entity="customer" />
}

export function SellerSelect(props: EntitySelectProps) {
  return <EntitySelect {...props} entity="seller" />
}

export function SupplierSelect(props: EntitySelectProps) {
  return <EntitySelect {...props} entity="supplier" />
}
