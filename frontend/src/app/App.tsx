import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom'
import { apiGet, apiPatch, apiPost, apiPut, clearAccessToken, getAccessToken, login, type ApiPage } from '../shared/api/client'
import { Button } from '../shared/components/Button'
import { Badge, type BadgeTone } from '../shared/components/Badge'
import { DataTable, type TableColumn } from '../shared/components/DataTable'
import { EmptyState } from '../shared/components/EmptyState'
import { PageHeader } from '../shared/components/PageHeader'
import { Panel } from '../shared/components/Panel'
import { StatCard } from '../shared/components/StatCard'
import CustomersPage from '../features/customers/CustomersPage'

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

function formatDate(value: unknown) {
  return value ? new Intl.DateTimeFormat('es-AR').format(new Date(String(value))) : '-'
}

function useApiPage<T>(path: string) {
  return useQuery({ queryKey: [path], queryFn: () => apiGet<ApiPage<T>>(path) })
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
      ['Inventario', '/inventory'],
      ['Listas de precios', '/price-lists'],
    ],
  },
  {
    label: 'Administración',
    links: [
      ['Usuarios', '/admin/users'],
      ['Vendedores', '/admin/sellers'],
      ['Auditoría', '/admin/audit'],
      ['Configuración', '/admin/settings'],
    ],
  },
]

function AppShell() {
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
          {menuGroups.map((group) => (
            <div className="nav-group" key={group.label}>
              <span className="nav-group-label">{group.label}</span>
              {group.links.map(([label, href]) => (
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
            <span className="avatar">AM</span>
            <span>
              <strong>Admin Martín</strong>
              <small>Administrador</small>
            </span>
          </div>
           <Button variant="ghost" fullWidth onClick={() => { clearAccessToken(); window.location.href = '/login' }}>Salir</Button>
        </div>
      </aside>

      <div className="main-area">
        <header className="topbar">
          <div className="breadcrumbs">Empresa / Operación</div>
          <div className="topbar-actions">
            <span className="connection-status"><span className="status-dot" /> Sistema operativo</span>
            <Button variant="secondary">Ayuda</Button>
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
            <Button href="/inventory" variant="secondary" fullWidth>Revisar stock</Button>
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

function OrdersPage() {
  const query = useApiPage<Record<string, unknown>>('/api/orders?page=0&size=20')
  const rows: Row[] = (query.data?.content ?? []).map((order) => ({ id: String(order.number), customer: String(order.customer), seller: String(order.seller), total: money(order.total), status: String(order.status) }))

  return (
    <>
      <PageHeader eyebrow="Operación" title="Pedidos" description="Consultá, confirmá y seguí el estado de los pedidos." actions={<Button href="/orders/new">+ Nuevo pedido</Button>} />
      <Panel>
        <div className="toolbar">
          <input className="input search-input" placeholder="Buscar por cliente o número..." aria-label="Buscar pedidos" />
          <select className="select" aria-label="Filtrar por estado"><option>Todos los estados</option><option>Confirmado</option><option>Entregado</option><option>Cancelado</option></select>
          <Button variant="secondary">Filtrar</Button>
        </div>
         {query.isLoading || query.isError ? <LoadState error={query.error} /> : <><DataTable columns={orderColumns} rows={rows} onRowClick={() => undefined} /><Pagination total={query.data?.totalElements} /></>}
      </Panel>
    </>
  )
}

function OrderCreatePage() {
  const productsQuery = useApiPage<Record<string, unknown>>('/api/products?page=0&size=2')
  const orderItems: Row[] = (productsQuery.data?.content ?? []).map((product) => ({ product: String(product.name), presentation: String(product.presentation), quantity: '1', price: money(product.price), total: money(product.price) }))
  return (
    <>
      <PageHeader eyebrow="Nuevo pedido" title="Crear pedido" description="El pedido se guarda cuando se confirma." actions={<Button variant="secondary" href="/orders">Cancelar</Button>} />
      <div className="content-grid two-thirds">
        <Panel title="Datos del pedido" description="Seleccioná el cliente y agregá los productos.">
          <div className="form-grid">
            <label className="field"><span>Cliente</span><select className="select"><option>Seleccionar cliente...</option><option>Almacén La Esquina</option><option>Despensa Central</option></select></label>
            <label className="field"><span>Vendedor asignado</span><input className="input" value="Lucía Gómez" readOnly /></label>
          </div>
          <div className="section-heading"><div><h3>Productos</h3><p>El precio se toma de la lista del cliente.</p></div><Button variant="secondary">+ Agregar producto</Button></div>
          {productsQuery.isLoading || productsQuery.isError ? <LoadState error={productsQuery.error} /> : <DataTable columns={orderItemColumns} rows={orderItems} />}
          <div className="order-totals"><span>Subtotal</span><strong>$ 184.500</strong><span>Descuento total</span><strong>$ 0</strong><span className="total-label">Total</span><strong className="total-value">$ 184.500</strong></div>
        </Panel>
        <Panel title="Cobro" description="Podés registrar pagos parciales o dejar saldo en cuenta corriente.">
          <div className="payment-options"><label className="radio-row"><input type="checkbox" /> Efectivo</label><label className="radio-row"><input type="checkbox" /> Transferencia</label><label className="radio-row"><input type="checkbox" /> Cuenta corriente</label></div>
          <label className="field"><span>Importe a cobrar</span><input className="input" placeholder="$ 0,00" /></label>
          <Button fullWidth>Confirmar pedido</Button>
          <p className="helper-text">El backend validará stock, precios, descuentos y límite de crédito.</p>
        </Panel>
      </div>
    </>
  )
}

const orderItemColumns: TableColumn[] = [
  { key: 'product', label: 'Producto', emphasis: true },
  { key: 'presentation', label: 'Presentación' },
  { key: 'quantity', label: 'Cantidad', align: 'right' },
  { key: 'price', label: 'Precio unitario', align: 'right' },
  { key: 'total', label: 'Total', align: 'right' },
]

function SalesPage() {
  const query = useApiPage<Record<string, unknown>>('/api/sales?page=0&size=20')
  const rows: Row[] = (query.data?.content ?? []).map((sale) => ({ id: String(sale.number), customer: String(sale.customer), date: formatDate(sale.date), total: money(sale.total), payment: Number(sale.balance) > 0 ? 'Cuenta corriente' : 'Pagada', status: String(sale.status) }))
  const columns: TableColumn[] = [
    { key: 'id', label: 'Venta', emphasis: true }, { key: 'customer', label: 'Cliente' }, { key: 'date', label: 'Fecha' }, { key: 'total', label: 'Total', align: 'right' }, { key: 'payment', label: 'Pago' }, { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
  ]
  return <><PageHeader eyebrow="Operación" title="Ventas" description="Consultá ventas, pagos y documentos." /><Panel><div className="toolbar"><input className="input search-input" placeholder="Buscar ventas..." aria-label="Buscar ventas" /><Button variant="secondary">Filtrar</Button></div>{query.isLoading || query.isError ? <LoadState error={query.error} /> : <><DataTable columns={columns} rows={rows} /><Pagination total={query.data?.totalElements} /></>}</Panel></>
}

function ProductForm({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState({ sku: '', name: '', category: '', presentation: 'Unidad', cost: '', price: '' })
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  function change(field: keyof typeof form, value: string) { setForm((current) => ({ ...current, [field]: value })) }
  async function submit(event: FormEvent) {
    event.preventDefault(); setSaving(true); setError('')
    try {
      await apiPost('/api/products', { ...form, cost: Number(form.cost), price: Number(form.price) })
      await queryClient.invalidateQueries({ queryKey: ['/api/products?page=0&size=20'] })
      onDone()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'No se pudo guardar el producto') }
    finally { setSaving(false) }
  }
  return <Panel title="Nuevo producto"><form className="form-grid" onSubmit={submit}>{(['sku', 'name', 'category', 'presentation', 'cost', 'price'] as const).map((field) => <label className="field" key={field}><span>{field === 'sku' ? 'SKU' : field === 'name' ? 'Nombre' : field === 'category' ? 'Categoría' : field === 'presentation' ? 'Presentación' : field === 'cost' ? 'Costo' : 'Precio'}</span><input className="input" type={field === 'cost' || field === 'price' ? 'number' : 'text'} min={field === 'cost' || field === 'price' ? '0' : undefined} step={field === 'cost' || field === 'price' ? '0.01' : undefined} value={form[field]} onChange={(event) => change(field, event.target.value)} required /></label>)}{error && <p className="error-text">{error}</p>}<div className="page-actions"><Button variant="secondary" type="button" onClick={onDone}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Guardar producto'}</Button></div></form></Panel>
}

function ProductsPage() {
  const query = useApiPage<Record<string, unknown>>('/api/products?page=0&size=20')
  const [showForm, setShowForm] = useState(false)
  const rows: Row[] = (query.data?.content ?? []).map((product) => ({ name: String(product.name), category: String(product.category), cost: money(product.cost), price: money(product.price), stock: String(product.stock) }))
  const columns: TableColumn[] = [{ key: 'name', label: 'Producto', emphasis: true }, { key: 'category', label: 'Categoría' }, { key: 'cost', label: 'Costo', align: 'right' }, { key: 'price', label: 'Lista general', align: 'right' }, { key: 'stock', label: 'Stock', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> }]
  return <><PageHeader eyebrow="Catálogo" title="Productos" description="Productos, marcas, presentaciones, costos y precios." actions={<Button onClick={() => setShowForm((value) => !value)}>+ Nuevo producto</Button>} />{showForm && <ProductForm onDone={() => setShowForm(false)} />}<Panel><div className="toolbar"><input className="input search-input" placeholder="Buscar productos..." aria-label="Buscar productos" /><select className="select" aria-label="Filtrar categoría"><option>Todas las categorías</option></select><Button variant="secondary">Filtrar</Button></div>{query.isLoading || query.isError ? <LoadState error={query.error} /> : <><DataTable columns={columns} rows={rows} /><Pagination total={query.data?.totalElements} /></>}</Panel></>
}

function InventoryPage() {
  const query = useApiPage<Record<string, unknown>>('/api/inventory?page=0&size=20')
  const rows: Row[] = (query.data?.content ?? []).map((item) => ({ product: String(item.product), stock: String(item.stock), lastMovement: String(item.lastMovement ?? '-'), updated: formatDate(item.updated) }))
  const columns: TableColumn[] = [{ key: 'product', label: 'Producto', emphasis: true }, { key: 'stock', label: 'Saldo actual', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> }, { key: 'lastMovement', label: 'Último movimiento' }, { key: 'updated', label: 'Actualizado' }]
  return <><PageHeader eyebrow="Catálogo" title="Inventario" description="Saldos actuales y movimientos de stock." actions={<Button>+ Ajustar stock</Button>} /><Panel title="Saldos por producto">{query.isLoading || query.isError ? <LoadState error={query.error} /> : <><DataTable columns={columns} rows={rows} /><Pagination total={query.data?.totalElements} /></>}</Panel></>
}

function PaymentsPage() {
  const query = useApiPage<Record<string, unknown>>('/api/payments?page=0&size=20')
  const rows: Row[] = (query.data?.content ?? []).map((payment) => ({ customer: String(payment.customer), sale: String(payment.sale), amount: money(payment.amount), method: String(payment.method), date: formatDate(payment.date), status: 'Registrado' }))
  const columns: TableColumn[] = [{ key: 'customer', label: 'Cliente', emphasis: true }, { key: 'sale', label: 'Venta' }, { key: 'amount', label: 'Importe', align: 'right' }, { key: 'method', label: 'Método' }, { key: 'date', label: 'Fecha' }, { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> }]
  return <><PageHeader eyebrow="Operación" title="Pagos y cuenta corriente" description="Registrá pagos parciales y consultá saldos pendientes." actions={<Button>+ Registrar pago</Button>} /><Panel><div className="toolbar"><input className="input search-input" placeholder="Buscar cliente..." aria-label="Buscar pagos" /><Button variant="secondary">Ver cuenta de cliente</Button></div>{query.isLoading || query.isError ? <LoadState error={query.error} /> : <><DataTable columns={columns} rows={rows} /><Pagination total={query.data?.totalElements} /></>}</Panel></>
}

function AdminUsersPage() {
  const query = useApiPage<Record<string, unknown>>('/api/users?page=0&size=20')
  const rows: Row[] = (query.data?.content ?? []).map((user) => ({ name: String(user.name), email: String(user.email), role: String(user.email).startsWith('admin') ? 'Administrador' : 'Vendedor', status: String(user.status) }))
  const columns: TableColumn[] = [{ key: 'name', label: 'Usuario', emphasis: true }, { key: 'email', label: 'Email' }, { key: 'role', label: 'Rol' }, { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> }, { key: 'actions', label: '', render: () => <Button variant="link">Ver detalle</Button> }]
  return <><PageHeader eyebrow="Administración" title="Usuarios" description="Usuarios, acceso, bloqueo y sesiones." actions={<Button>+ Nuevo usuario</Button>} /><Panel>{query.isLoading || query.isError ? <LoadState error={query.error} /> : <><DataTable columns={columns} rows={rows} /><Pagination total={query.data?.totalElements} /></>}</Panel></>
}

function PlaceholderPage({ title, description, action }: { title: string; description: string; action?: string }) {
  return <><PageHeader eyebrow="Módulo" title={title} description={description} actions={action ? <Button>{action}</Button> : undefined} /><Panel><EmptyState title="Vista preparada" description="La distribución de contenido está lista para conectar con la API." action={<Button variant="secondary">Configurar vista</Button>} /></Panel></>
}

function StatusBadge({ value }: { value: string }) {
  const tone: BadgeTone = value.toLowerCase().includes('cancel') || value.toLowerCase().includes('bloque') || value === 'Pendiente' ? 'muted' : value.toLowerCase().includes('entreg') || value === 'Pagada' || value === 'Activo' ? 'strong' : 'soft'
  return <Badge tone={tone}>{value}</Badge>
}

function Pagination({ total = 0 }: { total?: number }) {
  return <div className="pagination"><span>Mostrando hasta 20 de {total} resultados</span><div><Button variant="secondary" disabled>Anterior</Button><Button variant="secondary" disabled={total <= 20}>Siguiente</Button></div></div>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
      <Route element={<AppShell />}>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/orders/new" element={<OrderCreatePage />} />
        <Route path="/sales" element={<SalesPage />} />
        <Route path="/customers" element={<CustomersPage />} />
        <Route path="/products" element={<ProductsPage />} />
        <Route path="/inventory" element={<InventoryPage />} />
        <Route path="/payments" element={<PaymentsPage />} />
        <Route path="/price-lists" element={<PlaceholderPage title="Listas de precios" description="Administrá las listas y sus precios." action="+ Nueva lista" />} />
        <Route path="/admin/users" element={<AdminUsersPage />} />
        <Route path="/admin/sellers" element={<PlaceholderPage title="Vendedores" description="Perfiles comerciales y asignaciones." action="+ Nuevo vendedor" />} />
        <Route path="/admin/audit" element={<PlaceholderPage title="Auditoría" description="Historial de operaciones sensibles del sistema." />} />
        <Route path="/admin/settings" element={<PlaceholderPage title="Configuración" description="Parámetros globales de la operación." />} />
        <Route path="*" element={<PlaceholderPage title="Página no encontrada" description="La ruta solicitada no existe." action="Volver al resumen" />} />
      </Route>
      </Route>
    </Routes>
  )
}
