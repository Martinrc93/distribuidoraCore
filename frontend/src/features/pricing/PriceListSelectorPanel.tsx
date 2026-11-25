import { moveBetweenTabs } from '../../shared/tabList'
import { Button } from '../../shared/components/Button'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { EmptyState } from '../../shared/components/EmptyState'
import { Pagination } from '../../shared/components/Pagination'
import { Panel } from '../../shared/components/Panel'
import type { ListVisibility, PriceList } from './pricingTypes'

type Props = {
  isAdmin: boolean
  visibleLists: PriceList[]
  selectedId: string
  selectedList?: PriceList
  hasMultipleLists: boolean
  listVisibility: ListVisibility
  listExpanded: boolean
  listPage: number
  totalPages: number
  totalElements: number
  onChangeVisibility: (visibility: ListVisibility) => void
  onToggleExpanded: () => void
  onSelectList: (listId: string) => void
  onOpenRename: (list: PriceList) => void
  onOpenStatus: (list: PriceList) => void
  onPageChange: (page: number) => void
  onDocumentDone: (message: string) => void
}

export function PriceListSelectorPanel({
  isAdmin,
  visibleLists,
  selectedId,
  selectedList,
  hasMultipleLists,
  listVisibility,
  listExpanded,
  listPage,
  totalPages,
  totalElements,
  onChangeVisibility,
  onToggleExpanded,
  onSelectList,
  onOpenRename,
  onOpenStatus,
  onPageChange,
  onDocumentDone,
}: Props) {
  return <Panel
    title="Listas de precios"
    description={`${visibleLists.length} ${visibleLists.length === 1 ? 'lista disponible' : 'listas disponibles'} · Los pedidos usan la lista asignada al cliente.`}
    action={hasMultipleLists && <div className="price-list-controls">
      <label className="price-list-filter"><span>Mostrar</span><select className="select" aria-label="Mostrar listas" value={listVisibility} onChange={(event) => onChangeVisibility(event.target.value as ListVisibility)}>
        <option value="ALL">Todas</option><option value="ACTIVE">Activas</option><option value="INACTIVE">Inactivas</option>
      </select></label>
      <Button variant="secondary" aria-label={listExpanded ? 'Plegar listas' : 'Desplegar listas'} title={listExpanded ? 'Plegar listas' : 'Desplegar listas'} aria-expanded={listExpanded} aria-controls="price-list-selector" onClick={onToggleExpanded}>
        <svg className={`price-list-toggle-icon${listExpanded ? ' expanded' : ''}`} viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m5 7.5 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </Button>
    </div>}
  >
    {listExpanded && (visibleLists.length > 0 ? <div id="price-list-selector" className="price-list-selector" role="tablist" aria-label="Listas disponibles" onKeyDown={moveBetweenTabs}>
      {visibleLists.map((list, index) => <button className={`price-list-card${selectedId === list.id ? ' selected' : ''}`} type="button" role="tab"
        tabIndex={selectedId === list.id || index === 0 && !visibleLists.some(item => item.id === selectedId) ? 0 : -1}
        aria-selected={selectedId === list.id} key={list.id} onClick={() => onSelectList(list.id)}>
        <span className="price-list-card-heading"><strong>{list.name}</strong>{list.isDefault && <span className="price-list-default">Predeterminada</span>}</span>
        <span className="price-list-code">{list.code}</span>
        <span className={`price-list-status${list.status === 'ACTIVE' ? ' active' : ''}`}><i aria-hidden="true" />{list.status === 'ACTIVE' ? 'Activa' : 'Inactiva'}</span>
        {(list.pendingReviewCount ?? 0) > 0 && <span className="price-list-pending">{list.pendingReviewCount} sin revisar</span>}
      </button>)}
    </div> : <EmptyState title={`No hay listas ${listVisibility === 'ACTIVE' ? 'activas' : 'inactivas'}`} description="Elegí otro filtro para ver las listas disponibles." />)}
    {selectedList && <div className="page-actions price-list-actions">
      <DocumentActions key={selectedList.id} path={`/api/documents/price-lists/${selectedList.id}`}
        filename={`lista-${selectedList.code}.pdf`} downloadLabel={`Descargar ${selectedList.name}`} onDone={onDocumentDone} />
      {listExpanded && isAdmin && <>
        <Button variant="secondary" onClick={() => onOpenRename(selectedList)}>Renombrar {selectedList.name}</Button>
        <Button variant="secondary" onClick={() => onOpenStatus(selectedList)}>{selectedList.status === 'ACTIVE' ? `Desactivar ${selectedList.name}` : `Activar ${selectedList.name}`}</Button>
      </>}
    </div>}
    {totalPages > 1 && <Pagination summary={<>Página {listPage + 1} de {totalPages} · {totalElements} listas</>} onPrevious={() => onPageChange(listPage - 1)} onNext={() => onPageChange(listPage + 1)} previousDisabled={listPage === 0} nextDisabled={listPage + 1 >= totalPages} />}
  </Panel>
}
