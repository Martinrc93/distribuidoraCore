import AppShell from './AppShell'
import { lazy, useEffect, useState, useSyncExternalStore, type FormEvent, type ReactNode } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { getAccessToken, getLoginNotice, login, subscribeAuthChange } from '../shared/api/client'
import { hasAuthority, hasPermission } from '../shared/auth/permissions'
import { Button } from '../shared/components/Button'
import { AuthPageLayout } from '../shared/components/AuthPageLayout'
import { PasswordField } from '../shared/components/PasswordField'
import { EmptyState } from '../shared/components/EmptyState'
import { PageHeader } from '../shared/components/PageHeader'
import { Panel } from '../shared/components/Panel'
import { businessProfilePath, commissionPlansPagePath, commissionSettlementsPath, creditLimitPath, operationShortcutsPath, printersPath, settingsPath } from '../features/admin/settingsPaths'
import { RouteContent } from './RouteContent'

const DashboardPage = lazy(() => import('../features/dashboard/DashboardPage'))
const CustomersPage = lazy(() => import('../features/customers/CustomersPage'))
const ExpensesPage = lazy(() => import('../features/expenses/ExpensesPage'))
const SuppliersPage = lazy(() => import('../features/suppliers/SuppliersPage'))
const SupplierOrdersPage = lazy(() => import('../features/purchasing/SupplierOrdersPage'))
const SupplierOrderCreatePage = lazy(() => import('../features/purchasing/SupplierOrderCreatePage'))
const SupplierOrderDetailPage = lazy(() => import('../features/purchasing/SupplierOrderDetailPage'))
const SupplierOrderRestockPage = lazy(() => import('../features/purchasing/SupplierOrderRestockPage'))
const CustomerDetailPage = lazy(() => import('../features/customers/CustomerDetailPage'))
const CustomerPurchasesPage = lazy(() => import('../features/customers/CustomerPurchasesPage'))
const ProductsPage = lazy(() => import('../features/products/ProductsPage'))
const ShrinkagesPage = lazy(() => import('../features/inventory/ShrinkagesPage'))
const PriceListsPage = lazy(() => import('../features/pricing/PriceListsPage'))
const CatalogAdminPage = lazy(() => import('../features/catalog/CatalogAdminPage'))
const OrderCreatePage = lazy(() => import('../features/orders/OrderCreatePage'))
const OrdersPage = lazy(() => import('../features/orders/OrdersPage'))
const OrderDetailPage = lazy(() => import('../features/orders/OrderDetailPage'))
const SalesPage = lazy(() => import('../features/sales/SalesPage'))
const SoldProductsPage = lazy(() => import('../features/sales/SoldProductsPage'))
const SellersPage = lazy(() => import('../features/admin/SellersPage'))
const SettingsPage = lazy(() => import('../features/admin/SettingsPage'))
const BusinessProfilePage = lazy(() => import('../features/admin/BusinessProfilePage'))
const CreditLimitPage = lazy(() => import('../features/admin/CreditLimitPage'))
const OperationShortcutsPage = lazy(() => import('../features/admin/OperationShortcutsPage'))
const PrintersPage = lazy(() => import('../features/admin/PrintersPage'))
const CommissionPlansPage = lazy(() => import('../features/admin/CommissionPlansPage'))
const CommissionSettlementsPage = lazy(() => import('../features/admin/CommissionSettlementsPage'))
const AuditPage = lazy(() => import('../features/admin/AuditPage'))
const ZonesPage = lazy(() => import('../features/admin/ZonesPage'))
const AnalyticsDashboardPage = lazy(() => import('../features/dashboard/AnalyticsDashboardPage'))
const RolesPage = lazy(() => import('../features/admin/RolesPage'))
const ActivateUserPage = lazy(() => import('../features/admin/UsersPage').then(module => ({ default: module.ActivateUserPage })))
const UsersPage = lazy(() => import('../features/admin/UsersPage').then(module => ({ default: module.UsersPage })))

function RequireAuth() {
  const token = useSyncExternalStore(subscribeAuthChange, getAccessToken)
  const queryClient = useQueryClient()
  useEffect(() => subscribeAuthChange(() => {
    if (getAccessToken()) return
    void queryClient.cancelQueries()
    queryClient.clear()
  }), [queryClient])
  return token ? <Outlet /> : <Navigate to="/login" replace />
}

function RequireAdmin({ children }: { children: ReactNode }) {
  return hasAuthority('ADMIN_ALL') ? children : <Navigate to="/orders" replace />
}

function LegacyCommissionSettlements() {
  const { search, hash } = useLocation()
  return <Navigate to={{ pathname: commissionSettlementsPath, search, hash }} replace />
}

function RequireCustomerView() {
  return hasPermission('CUSTOMER_VIEW') ? <CustomerDetailPage /> : <Navigate to="/orders" replace />
}

function RequireReportsView({ children, back }: { children: ReactNode; back: string }) {
  return hasPermission('REPORTS_VIEW') ? children : <Navigate to={back} replace />
}

function RequireOrderCreate() {
  return hasAuthority('ADMIN_ALL') || hasAuthority('ORDER_CREATE') ? <OrderCreatePage /> : <Navigate to="/orders" replace />
}

function LoginPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
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
      await queryClient.cancelQueries()
      queryClient.clear()
      navigate('/dashboard', { replace: true })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'No se pudo iniciar sesión')
    } finally {
      setSubmitting(false)
    }
  }

  const roleUpdated = location.state?.userRoleUpdated === true || getLoginNotice() === 'userRoleUpdated'
  const permissionsUpdated = location.state?.rolePermissionsUpdated === true || getLoginNotice() === 'rolePermissionsUpdated'
  return <AuthPageLayout>
    <Panel title="Ingresar" description="Usá un usuario creado en la base de datos.">
      {(roleUpdated || permissionsUpdated) && <p className="success-text" role="status">
        {roleUpdated ? 'El rol de tu usuario se actualizó. Volvé a ingresar para continuar.' : 'Los permisos de tu rol se guardaron. Volvé a ingresar para continuar con los permisos actualizados.'}
      </p>}
      <form className="login-form" onSubmit={submit}>
        <label className="field">
          <span>Email</span>
          <input className="input" type="email" autoComplete="username" value={email} onChange={(event) => setEmail(event.target.value)} required />
        </label>
        <PasswordField label="Contraseña" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
        {error && <p className="error-text" role="alert">{error}</p>}
        <Button fullWidth disabled={submitting}>{submitting ? 'Ingresando...' : 'Ingresar'}</Button>
      </form>
    </Panel>
  </AuthPageLayout>
}

function PlaceholderPage({ title, description }: { title: string; description: string }) {
  return <><PageHeader title={title} description={description} /><Panel><EmptyState title="Página no disponible" description="Volvé al resumen para continuar con una pantalla conectada." action={<Button variant="secondary" href="/dashboard">Volver al resumen</Button>} /></Panel></>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/activate" element={<RouteContent><ActivateUserPage /></RouteContent>} />
      <Route element={<RequireAuth />}>
      <Route element={<AppShell />}>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<RequireAdmin><DashboardPage /></RequireAdmin>} />
        <Route path="/analytics" element={<RequireAdmin><AnalyticsDashboardPage /></RequireAdmin>} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/orders/new" element={<RequireOrderCreate />} />
        <Route path="/orders/:orderId" element={<OrderDetailPage />} />
        <Route path="/sales" element={<SalesPage />} />
        <Route path="/sales/products" element={<RequireReportsView back="/sales"><SoldProductsPage /></RequireReportsView>} />
        <Route path="/sales/products/:productId" element={<RequireReportsView back="/sales"><SoldProductsPage /></RequireReportsView>} />
        <Route path="/supplier-orders" element={<RequireAdmin><SupplierOrdersPage /></RequireAdmin>} />
        <Route path="/supplier-orders/new" element={<RequireAdmin><SupplierOrderCreatePage /></RequireAdmin>} />
        <Route path="/supplier-orders/restock" element={<RequireAdmin><SupplierOrderRestockPage /></RequireAdmin>} />
        <Route path="/supplier-orders/:orderId" element={<RequireAdmin><SupplierOrderDetailPage /></RequireAdmin>} />
        <Route path="/customers" element={<CustomersPage />} />
        <Route path="/expenses" element={<RequireAdmin><ExpensesPage /></RequireAdmin>} />
        <Route path={commissionSettlementsPath} element={<RequireAdmin><CommissionSettlementsPage /></RequireAdmin>} />
        <Route path="/suppliers" element={<RequireAdmin><SuppliersPage /></RequireAdmin>} />
        <Route path="/customers/:customerId" element={<RequireCustomerView />} />
        <Route path="/customers/:customerId/purchases" element={<RequireReportsView back="/customers"><CustomerPurchasesPage /></RequireReportsView>} />
        <Route path="/products" element={<RequireAdmin><ProductsPage /></RequireAdmin>} />
        <Route path="/products/shrinkages" element={<RequireAdmin><ShrinkagesPage /></RequireAdmin>} />
        <Route path="/catalog" element={<RequireAdmin><CatalogAdminPage /></RequireAdmin>} />
        <Route path="/price-lists" element={<RequireAdmin><PriceListsPage /></RequireAdmin>} />
        <Route path="/payments" element={<Navigate to="/orders" replace />} />
        <Route path="/admin/users" element={<RequireAdmin><UsersPage /></RequireAdmin>} />
        <Route path="/admin/roles" element={<RequireAdmin><RolesPage /></RequireAdmin>} />
        <Route path="/admin/sellers" element={<RequireAdmin><SellersPage /></RequireAdmin>} />
        <Route path="/admin/zones" element={<RequireAdmin><ZonesPage /></RequireAdmin>} />
        <Route path={settingsPath} element={<RequireAdmin><SettingsPage /></RequireAdmin>} />
        <Route path={businessProfilePath} element={<RequireAdmin><BusinessProfilePage /></RequireAdmin>} />
        <Route path={creditLimitPath} element={<RequireAdmin><CreditLimitPage /></RequireAdmin>} />
        <Route path={commissionPlansPagePath} element={<RequireAdmin><CommissionPlansPage /></RequireAdmin>} />
        <Route path={operationShortcutsPath} element={<RequireAdmin><OperationShortcutsPage /></RequireAdmin>} />
        <Route path={printersPath} element={<RequireAdmin><PrintersPage /></RequireAdmin>} />
        <Route path={`${commissionPlansPagePath}/settlements`} element={<RequireAdmin><LegacyCommissionSettlements /></RequireAdmin>} />
        <Route path="/admin/audit" element={<RequireAdmin><AuditPage /></RequireAdmin>} />
        <Route path="*" element={<PlaceholderPage title="Página no encontrada" description="La ruta solicitada no existe." />} />
      </Route>
      </Route>
    </Routes>
  )
}
