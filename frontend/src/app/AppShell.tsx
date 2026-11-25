import { trapDialogFocus } from '../shared/dialogFocus'
import { lockBodyScroll } from '../shared/lockBodyScroll'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { RouteContent } from './RouteContent'
import { logout } from '../shared/api/client'
import { getCurrentUserEmail, getCurrentUserId, hasAuthority } from '../shared/auth/permissions'
import { businessLogoQuery, businessLogoSrc } from '../shared/businessLogo'
import { Button } from '../shared/components/Button'
import { ThemeToggle } from '../shared/components/ThemeToggle'
import { TextSizeToggle } from '../shared/components/TextSizeToggle'
import { SettingsEditorProvider } from '../features/admin/SettingsEditorState'
import { settingsPath } from '../features/admin/settingsPaths'
import { formatShortcut, operationLinks, shortcutFromKey, useOperationShortcuts, type OperationShortcuts } from './operationShortcuts'

const compactNavigationQuery = '(max-width: 1050px)'

function routeTitle(pathname: string, fallback: string) {
  if (pathname === '/orders/new') return 'Nuevo pedido'
  if (/^\/orders\/[^/]+$/.test(pathname)) return 'Detalle de pedido'
  if (/^\/sales\/products\/[^/]+$/.test(pathname)) return 'Historial por artículo'
  if (pathname === '/sales/products') return 'Artículos vendidos'
  if (/^\/customers\/[^/]+\/purchases$/.test(pathname)) return 'Compras del cliente'
  if (/^\/customers\/[^/]+$/.test(pathname)) return 'Ficha de cliente'
  if (pathname === '/products/shrinkages') return 'Mermas de stock'
  if (pathname === '/supplier-orders/new') return 'Nueva compra'
  if (pathname === '/supplier-orders/restock') return 'Reponer stock'
  if (/^\/supplier-orders\/[^/]+$/.test(pathname)) return 'Detalle de compra'
  if (pathname === '/admin/settings/business-profile') return 'Perfil comercial'
  if (pathname === '/admin/settings/credit-limit') return 'Límite de crédito'
  if (pathname === '/admin/settings/commissions') return 'Planes de comisión'
  if (pathname === '/admin/settings/shortcuts') return 'Atajos de teclado'
  return fallback
}
const menuGroups: { id: string; label: string; links: readonly (readonly [string, string, boolean?])[] }[] = [
  { id: 'operation', label: 'Operación', links: operationLinks },
  { id: 'catalog', label: 'Catálogo', links: [['Stock', '/products'], ['Marcas y categorías', '/catalog'], ['Listas de precios', '/price-lists'], ['Proveedores', '/suppliers']] },
  { id: 'administration', label: 'Administración', links: [['Usuarios', '/admin/users'], ['Roles y permisos', '/admin/roles'], ['Vendedores', '/admin/sellers'], ['Zonas', '/admin/zones'], ['Configuración', settingsPath], ['Auditoría', '/admin/audit']] },
]

type NavigationState = {
  userEmail?: string
  expandedGroups: string[]
  activeGroupId?: string
  shortcuts: OperationShortcuts
  onToggleGroup: (id: string) => void
}

function BrandMark({ logoSrc }: { logoSrc?: string }) {
  return logoSrc ? <img className="brand-mark brand-mark-image" src={logoSrc} alt="" width="32" height="32" />
    : <span className="brand-mark" aria-hidden="true">D</span>
}

function Brand({ logoSrc }: { logoSrc?: string }) {
  return <div className="brand-block"><BrandMark logoSrc={logoSrc} /><div><strong>Distribuidora</strong><span>Gestión comercial</span></div></div>
}

function NavigationContent({ isAdmin, userEmail, expandedGroups, activeGroupId, shortcuts, onToggleGroup, onNavigate, onSignOut }: NavigationState & { isAdmin: boolean; onNavigate?: () => void; onSignOut: () => void }) {
  return <>
    <nav id="primary-navigation" className="main-nav" aria-label="Navegación principal">
      {menuGroups.filter((group) => group.id === 'operation' || isAdmin).map((group) => (
        <div className="nav-group" key={group.id}>
          <button type="button" className={`nav-group-toggle${activeGroupId === group.id ? ' is-current' : ''}`}
            data-current={activeGroupId === group.id} aria-expanded={expandedGroups.includes(group.id)}
            aria-controls={`navigation-${group.id}-links`} onClick={() => onToggleGroup(group.id)}>
            <span>{group.label}</span>
            <svg className="nav-group-chevron" width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m6 8 4 4 4-4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </button>
          <div id={`navigation-${group.id}-links`} className="nav-group-links" hidden={!expandedGroups.includes(group.id)}>
            {group.links.filter(([, , adminOnly]) => !adminOnly || isAdmin).map(([label, href]) => {
              const shortcut = shortcuts[href]
              return <NavLink className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`} key={href} to={href} onClick={onNavigate} aria-keyshortcuts={shortcut}>
                <span className="nav-dot" aria-hidden="true" />{label}
                {shortcut && <kbd className="ml-auto shrink-0 whitespace-nowrap font-sans text-[11px] font-normal" aria-hidden="true">{formatShortcut(shortcut)}</kbd>}
              </NavLink>
            })}
          </div>
        </div>
      ))}
    </nav>
    <div className="sidebar-footer">
      <div className="user-chip"><span className="avatar" aria-hidden="true">D</span><span><strong>Sesión activa</strong><small>{userEmail ?? (isAdmin ? 'Administrador' : 'Usuario')}</small></span></div>
      <Button type="button" variant="ghost" fullWidth onClick={onSignOut}>Salir</Button>
    </div>
  </>
}

function NavigationDrawer({ isAdmin, logoSrc, onClose, onNavigate, onSignOut, ...navigationState }: NavigationState & { isAdmin: boolean; logoSrc?: string; onClose: () => void; onNavigate: () => void; onSignOut: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const navigated = useRef(false)

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScroll = lockBodyScroll()
    element.showModal()
    element.querySelector<HTMLButtonElement>('[data-navigation-close]')?.focus()
    return () => {
      element.close()
      releaseScroll()
      if (navigated.current) return
      const target = window.matchMedia?.(compactNavigationQuery).matches === false
        ? document.querySelector<HTMLElement>('.sidebar .nav-group-links:not([hidden]) .nav-link.active')
          ?? document.querySelector<HTMLElement>('.sidebar .nav-group-toggle[data-current="true"]')
        : trigger
      if (target?.isConnected) target.focus({ preventScroll: true })
    }
  }, [])

  return <dialog ref={dialog} id="navigation-drawer" className="navigation-drawer" aria-label="Menú principal" aria-modal="true"
    onCancel={(event) => { event.preventDefault(); onClose() }}
    onKeyDown={trapDialogFocus}
    onClick={(event) => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose()
    }}>
    <div className="navigation-drawer-header"><Brand logoSrc={logoSrc} /><button type="button" className="navigation-close" aria-label="Cerrar menú" data-navigation-close onClick={onClose}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg></button></div>
    <NavigationContent {...navigationState} isAdmin={isAdmin} onNavigate={() => { navigated.current = true; onNavigate() }} onSignOut={onSignOut} />
  </dialog>
}

export default function AppShell() {
  const navigate = useNavigate()
  const logo = useQuery(businessLogoQuery)
  const logoSrc = businessLogoSrc(logo.data?.content)
  const location = useLocation()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const { shortcuts } = useOperationShortcuts()
  const main = useRef<HTMLElement>(null)
  const focusAfterNavigation = useRef(false)
  const [compact, setCompact] = useState(() => window.matchMedia?.(compactNavigationQuery).matches ?? false)
  const [drawerLocationKey, setDrawerLocationKey] = useState<string | null>(null)
  const drawerOpen = compact && drawerLocationKey === location.key
  useLayoutEffect(() => { setDrawerLocationKey(null) }, [location.key])
  const currentGroup = menuGroups.find((group) => group.links.some(([, href]) => location.pathname === href || location.pathname.startsWith(`${href}/`)))
  const currentLabel = currentGroup?.links.find(([, href]) => location.pathname === href || location.pathname.startsWith(`${href}/`))?.[0] ?? 'Gestión comercial'
  const activeGroupId = currentGroup?.id
  const [expandedGroups, setExpandedGroups] = useState<string[]>(() => [activeGroupId ?? 'operation'])
  const userEmail = getCurrentUserEmail()
  const navigationState: NavigationState = {
    userEmail,
    expandedGroups,
    activeGroupId,
    shortcuts,
    onToggleGroup: (id) => setExpandedGroups((groups) => groups.includes(id) ? groups.filter((group) => group !== id) : [...groups, id]),
  }

  useEffect(() => {
    if (activeGroupId) setExpandedGroups((groups) => groups.includes(activeGroupId) ? groups : [...groups, activeGroupId])
  }, [activeGroupId, location.pathname])

  const pageTitle = routeTitle(location.pathname, currentLabel)

  useEffect(() => {
    document.title = `${pageTitle} · Distribuidora`
  }, [pageTitle])

  useEffect(() => {
    if (!focusAfterNavigation.current) return
    focusAfterNavigation.current = false
    main.current?.focus()
  }, [location])

  useEffect(() => {
    function handleShortcut(event: KeyboardEvent) {
      if (event.defaultPrevented) return
      const shortcut = shortcutFromKey(event)
      if (!shortcut) return
      const destination = operationLinks.find(([, path, adminOnly]) => (!adminOnly || isAdmin) && shortcuts[path] === shortcut)
      if (!destination) return
      const target = event.target
      if (target instanceof HTMLElement && (target.isContentEditable || target.closest('input, textarea, select, [role="textbox"], [role="combobox"]'))) return
      if (document.querySelector('dialog[open], [aria-modal="true"], .modal-backdrop')) return

      event.preventDefault()
      focusAfterNavigation.current = true
      navigate(destination[1])
    }
    window.addEventListener('keydown', handleShortcut)
    return () => window.removeEventListener('keydown', handleShortcut)
  }, [isAdmin, navigate, shortcuts])

  useEffect(() => {
    const media = window.matchMedia?.(compactNavigationQuery)
    if (!media) return
    function update(event: MediaQueryListEvent) {
      setCompact(event.matches)
      setDrawerLocationKey(null)
    }
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])

  async function signOut() {
    await logout().catch(() => undefined)
  }

  return <div className="app-shell">
    {!compact && <aside className="sidebar"><Brand logoSrc={logoSrc} /><NavigationContent {...navigationState} isAdmin={isAdmin} onSignOut={signOut} /></aside>}
    <div className="main-area">
      <header className="topbar">
        <div className="breadcrumbs"><span>{currentGroup?.label ?? 'Empresa'}</span><span aria-hidden="true">/</span><strong>{currentLabel}</strong></div>
        {compact && <div className="compact-brand"><BrandMark logoSrc={logoSrc} /><div><strong>Distribuidora</strong><span>{currentLabel}</span></div></div>}
        <div className="topbar-controls">
          <TextSizeToggle />
          <ThemeToggle />
          {compact && <>
          <button type="button" className="navigation-toggle" aria-label="Abrir menú" aria-haspopup="dialog" aria-expanded={drawerOpen} aria-controls="navigation-drawer" onClick={() => setDrawerLocationKey(location.key)}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg><span>Menú</span></button>
          </>}
        </div>
        {userEmail && <div className="topbar-actions"><span className="topbar-user" title={userEmail}>{userEmail}</span></div>}
      </header>
      <main ref={main} className="page-content" tabIndex={-1}>
        <RouteContent>
          {isAdmin ? <SettingsEditorProvider key={getCurrentUserId()}><Outlet /></SettingsEditorProvider> : <Outlet />}
        </RouteContent>
      </main>
    </div>
    {drawerOpen && <NavigationDrawer {...navigationState} isAdmin={isAdmin} logoSrc={logoSrc} onClose={() => setDrawerLocationKey(null)}
      onNavigate={() => { focusAfterNavigation.current = true; setDrawerLocationKey(null) }} onSignOut={signOut} />}
  </div>
}
