import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom'
import { apiGet, getAccessToken, login, logout } from '../shared/api/client'
import { hasAuthority } from '../shared/auth/permissions'
import { Button } from '../shared/components/Button'
import { Badge, type BadgeTone } from '../shared/components/Badge'
import { DataTable, type TableColumn } from '../shared/components/DataTable'
import { EmptyState } from '../shared/components/EmptyState'
import { PageHeader } from '../shared/components/PageHeader'
import { Panel } from '../shared/components/Panel'
import { StatCard } from '../shared/components/StatCard'
import CustomersPage from '../features/customers/CustomersPage'
import ProductsPage from '../features/products/ProductsPage'
import PriceListsPage from '../features/pricing/PriceListsPage'
import CatalogAdminPage from '../features/catalog/CatalogAdminPage'
import OrderCreatePage from '../features/orders/OrderCreatePage'
import OrdersPage from '../features/orders/OrdersPage'
import OrderDetailPage from '../features/orders/OrderDetailPage'
import InventoryPage from '../features/inventory/InventoryPage'
import SalesPage from '../features/sales/SalesPage'
import PaymentsPage from '../features/payments/PaymentsPage'
import SellersPage from '../features/admin/SellersPage'
import CreditLimitPage from '../features/admin/CreditLimitPage'
import { ActivateUserPage, UsersPage } from '../features/admin/UsersPage'
import AuditPage from '../features/admin/AuditPage'

type Row = Record<string, string>

type DashboardData = {
  confirmedOrders: number
  todaySales: number
  pendingBalance: number
  negativeStock: number
  recentOrders: Array<{ id: string; customer: string; seller: string; total: number; status: string }>
}

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(Number(value ?? 0))
}

function LoadState({ error }: { error?: Error | null }) {
  if (error) return <EmptyState title="No se pudieron cargar los datos" description={error.message} />
  return <EmptyState title="Cargando datos" description="Consultando PostgreSQL a través de la API." />
}

function RequireAuth() {
  return getAccessToken() ? <Outlet /> : <Navigate to="/login" replace />
}

function LoginPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await login(email, password)
      navigate('/dashboard', { replace: true })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'No se pudo iniciar sesión')
    } finally {
      setSubmitting(false)
    }
  }

  return <main className="login-page"><Panel title="Ingresar" description="Usá un usuario creado en la base de datos."><form className="login-form" onSubmit={submit}><label className="field"><span>Email</span><input className="input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label><label className="field"><span>Contraseña</span><input className="input" type="password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>{error && <p className="error-text">{error}</p>}<Button fullWidth disabled={submitting}>{submitting ? 'Ingresando...' : 'Ingresar'}</Button></form></Panel></main>
}

const menuGroups = [
  {
    label: 'Operación',
    links: [
      ['Resumen', '/dashboard'],
      ['Pedidos', '/orders'],
      ['Ventas', '/sales'],
      ['Clientes', '/customers'],
      ['Pagos y deuda', '/payments'],
    ],
  },
  {
    label: 'Catálogo',
    links: [
      ['Productos', '/products'],
      ['Marcas y categorías', '/catalog'],
      ['Inventario', '/inventory'],
      ['Listas de precios', '/price-lists'],
    ],
  },
  {
    label: 'Administración',
    links: [
      ['Usuarios', '/admin/users'],
      ['Vendedores', '/admin/sellers'],
      ['Configuración', '/admin/settings'],
      ['Auditoría', '/admin/audit'],
    ],
  },
]

function AppShell() {
  const navigate = useNavigate()
  const isAdmin = hasAuthority('ADMIN_ALL')

  async function signOut() {
    await logout().catch(() => undefined)
    navigate('/login', { replace: true })
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-block">
          <span className="brand-mark">D</span>
          <div>
            <strong>Distribuidora</strong>
            <span>Gestión comercial</span>
          </div>
        </div>

        <nav className="main-nav" aria-label="Navegación principal">
          {menuGroups.filter((group) => group.label !== 'Administración' || isAdmin).map((group) => (
            <div className="nav-group" key={group.label}>
              <span className="nav-group-label">{group.label}</span>
              {group.links.filter(([, href]) => (href !== '/inventory' || isAdmin) && (href !== '/dashboard' || isAdmin)).map(([label, href]) => (
                <NavLink
                  className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
                  key={href}
                  to={href}
                >
                  <span className="nav-dot" aria-hidden="true" />
                  {label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="user-chip">
            <span className="avatar" aria-hidden="true">D</span>
            <span>
              <strong>Sesión activa</strong>
              <small>{isAdmin ? 'Administrador' : 'Usuario'}</small>
            </span>
          </div>
          <Button variant="ghost" fullWidth onClick={signOut}>Salir</Button>
        </div>
      </aside>

      <div className="main-area">
        <header className="topbar">
          <div className="breadcrumbs">Empresa / Operación</div>
          <div className="topbar-actions">
            <span className="connection-status"><span className="status-dot" /> Sistema operativo</span>
          </div>
        </header>
        <main className="page-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function DashboardPage() {
  const query = useQuery({ queryKey: ['dashboard'], queryFn: () => apiGet<DashboardData>('/api/dashboard') })
  if (query.isLoading || query.isError || !query.data) return <><PageHeader eyebrow="Operación" title="Resumen operativo" description="Una vista rápida de la actividad comercial de hoy." /><LoadState error={query.error} /></>
  const recentOrders: Row[] = query.data.recentOrders.map((order) => ({ id: order.id, customer: order.customer, seller: order.seller, total: money(order.total), status: order.status }))

  return (
    <>
      <PageHeader
         eyebrow="Datos en tiempo real"
        title="Resumen operativo"
        description="Una vista rápida de la actividad comercial de hoy."
        actions={<Button href="/orders/new">+ Nuevo pedido</Button>}
      />
      <section className="stats-grid" aria-label="Indicadores principales">
         <StatCard label="Pedidos confirmados" value={String(query.data.confirmedOrders)} detail="Desde PostgreSQL" />
         <StatCard label="Ventas del día" value={money(query.data.todaySales)} detail="Ventas registradas hoy" />
         <StatCard label="Deuda pendiente" value={money(query.data.pendingBalance)} detail="Saldo de clientes" />
         <StatCard label="Stock negativo" value={String(query.data.negativeStock)} detail="Productos a revisar" emphasis />
      </section>
      <div className="content-grid two-thirds">
        <Panel title="Pedidos recientes" action={<Button variant="link" href="/orders">Ver todos</Button>}>
          <DataTable columns={orderColumns} rows={recentOrders} />
        </Panel>
        <Panel title="Acciones rápidas">
          <div className="quick-actions">
            <Button href="/orders/new" fullWidth>Crear pedido</Button>
            <Button href="/customers" variant="secondary" fullWidth>Buscar cliente</Button>
            <Button href="/payments" variant="secondary" fullWidth>Registrar pago</Button>
            {hasAuthority('ADMIN_ALL') && <Button href="/inventory" variant="secondary" fullWidth>Revisar stock</Button>}
          </div>
        </Panel>
      </div>
    </>
  )
}

const orderColumns: TableColumn[] = [
  { key: 'id', label: 'Pedido', emphasis: true },
  { key: 'customer', label: 'Cliente' },
  { key: 'seller', label: 'Vendedor' },
  { key: 'total', label: 'Total', align: 'right' },
  { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
]

function PlaceholderPage({ title, description }: { title: string; description: string }) {
  return <><PageHeader eyebrow="Módulo" title={title} description={description} /><Panel><EmptyState title="Página no disponible" description="Volvé al resumen para continuar con una pantalla conectada." action={<Button variant="secondary" href="/dashboard">Volver al resumen</Button>} /></Panel></>
}

function StatusBadge({ value }: { value: string }) {
  const tone: BadgeTone = value.toLowerCase().includes('cancel') || value.toLowerCase().includes('bloque') || value === 'Pendiente' ? 'muted' : value.toLowerCase().includes('entreg') || value === 'Pagada' || value === 'Activo' ? 'strong' : 'soft'
  return <Badge tone={tone}>{value}</Badge>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/activate" element={<ActivateUserPage />} />
      <Route element={<RequireAuth />}>
      <Route element={<AppShell />}>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={hasAuthority('ADMIN_ALL') ? <DashboardPage /> : <Navigate to="/orders" replace />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/orders/new" element={<OrderCreatePage />} />
        <Route path="/orders/:orderId" element={<OrderDetailPage />} />
        <Route path="/sales" element={<SalesPage />} />
        <Route path="/customers" element={<CustomersPage />} />
        <Route path="/products" element={<ProductsPage />} />
        <Route path="/catalog" element={<CatalogAdminPage />} />
        <Route path="/price-lists" element={<PriceListsPage />} />
        <Route path="/inventory" element={<InventoryPage />} />
        <Route path="/payments" element={<PaymentsPage />} />
        <Route path="/admin/users" element={<UsersPage />} />
        <Route path="/admin/sellers" element={<SellersPage />} />
        <Route path="/admin/settings" element={<CreditLimitPage />} />
        <Route path="/admin/audit" element={hasAuthority('ADMIN_ALL') ? <AuditPage /> : <Navigate to="/orders" replace />} />
        <Route path="*" element={<PlaceholderPage title="Página no encontrada" description="La ruta solicitada no existe." />} />
      </Route>
      </Route>
    </Routes>
  )
}
