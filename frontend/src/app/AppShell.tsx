import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { logout } from '../shared/api/client'
import { hasAuthority } from '../shared/auth/permissions'
import { Button } from '../shared/components/Button'

const compactNavigationQuery = '(max-width: 1050px)'
const menuGroups = [
  { label: 'Operación', links: [['Resumen', '/dashboard'], ['Dashboard', '/analytics'], ['Pedidos', '/orders'], ['Ventas', '/sales'], ['Clientes', '/customers'], ['Pagos y deuda', '/payments']] },
  { label: 'Catálogo', links: [['Productos', '/products'], ['Marcas y categorías', '/catalog'], ['Listas de precios', '/price-lists']] },
  { label: 'Administración', links: [['Usuarios', '/admin/users'], ['Vendedores', '/admin/sellers'], ['Zonas', '/admin/zones'], ['Configuración', '/admin/settings'], ['Auditoría', '/admin/audit']] },
]

function Brand() {
  return <div className="brand-block"><span className="brand-mark" aria-hidden="true">D</span><div><strong>Distribuidora</strong><span>Gestión comercial</span></div></div>
}

function NavigationContent({ isAdmin, onNavigate, onSignOut }: { isAdmin: boolean; onNavigate?: () => void; onSignOut: () => void }) {
  return <>
    <nav id="primary-navigation" className="main-nav" aria-label="Navegación principal">
      {menuGroups.filter((group) => group.label !== 'Administración' || isAdmin).map((group) => (
        <div className="nav-group" key={group.label}>
          <span className="nav-group-label">{group.label}</span>
          {group.links.filter(([, href]) => !['/dashboard', '/analytics'].includes(href) || isAdmin).map(([label, href]) => (
            <NavLink className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`} key={href} to={href} onClick={onNavigate}>
              <span className="nav-dot" aria-hidden="true" />{label}
            </NavLink>
          ))}
        </div>
      ))}
    </nav>
    <div className="sidebar-footer">
      <div className="user-chip"><span className="avatar" aria-hidden="true">D</span><span><strong>Sesión activa</strong><small>{isAdmin ? 'Administrador' : 'Usuario'}</small></span></div>
      <Button type="button" variant="ghost" fullWidth onClick={onSignOut}>Salir</Button>
    </div>
  </>
}

function NavigationDrawer({ isAdmin, onClose, onSignOut }: { isAdmin: boolean; onClose: () => void; onSignOut: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    element.showModal()
    element.querySelector<HTMLButtonElement>('[data-navigation-close]')?.focus()
    return () => {
      element.close()
      document.body.style.overflow = previousOverflow
      const target = window.matchMedia?.(compactNavigationQuery).matches === false
        ? document.querySelector<HTMLElement>('.sidebar .nav-link.active')
        : trigger
      if (target?.isConnected) target.focus({ preventScroll: true })
    }
  }, [])

  return <dialog ref={dialog} id="navigation-drawer" className="navigation-drawer" aria-label="Menú principal" aria-modal="true"
    onCancel={(event) => { event.preventDefault(); onClose() }}
    onKeyDown={(event) => {
      if (event.key !== 'Tab') return
      const controls = event.currentTarget.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')
      const first = controls[0]
      const last = controls[controls.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last?.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first?.focus()
      }
    }}
    onClick={(event) => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose()
    }}>
    <div className="navigation-drawer-header"><Brand /><button type="button" className="navigation-close" aria-label="Cerrar menú" data-navigation-close onClick={onClose}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg></button></div>
    <NavigationContent isAdmin={isAdmin} onNavigate={onClose} onSignOut={onSignOut} />
  </dialog>
}

export default function AppShell() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const location = useLocation()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const [compact, setCompact] = useState(() => window.matchMedia?.(compactNavigationQuery).matches ?? false)
  const [drawerLocationKey, setDrawerLocationKey] = useState<string | null>(null)
  const drawerOpen = compact && drawerLocationKey === location.key
  const currentGroup = menuGroups.find((group) => group.links.some(([, href]) => location.pathname === href || location.pathname.startsWith(`${href}/`)))
  const currentLabel = currentGroup?.links.find(([, href]) => location.pathname === href || location.pathname.startsWith(`${href}/`))?.[0] ?? 'Gestión comercial'

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
    await queryClient.cancelQueries()
    queryClient.clear()
    navigate('/login', { replace: true })
  }

  return <div className="app-shell">
    {!compact && <aside className="sidebar"><Brand /><NavigationContent isAdmin={isAdmin} onSignOut={signOut} /></aside>}
    <div className="main-area">
      <header className="topbar">
        <div className="breadcrumbs"><span>{currentGroup?.label ?? 'Empresa'}</span><span aria-hidden="true">/</span><strong>{currentLabel}</strong></div>
        {compact && <>
          <div className="compact-brand"><span className="brand-mark" aria-hidden="true">D</span><div><strong>Distribuidora</strong><span>{currentLabel}</span></div></div>
          <button type="button" className="navigation-toggle" aria-label="Abrir menú" aria-haspopup="dialog" aria-expanded={drawerOpen} aria-controls="navigation-drawer" onClick={() => setDrawerLocationKey(location.key)}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg><span>Menú</span></button>
        </>}
        <div className="topbar-actions"><span className="connection-status"><span className="status-dot" aria-hidden="true" />Sistema operativo</span></div>
      </header>
      <main className="page-content"><Outlet /></main>
    </div>
    {drawerOpen && <NavigationDrawer isAdmin={isAdmin} onClose={() => setDrawerLocationKey(null)} onSignOut={signOut} />}
  </div>
}
