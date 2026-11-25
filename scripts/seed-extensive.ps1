param(
    [Parameter(Mandatory)][string]$ContainerName,
    [string]$Database = 'distribuidora',
    [string]$Username = 'distribuidora',
    [switch]$NoBackup
)

$ErrorActionPreference = 'Stop'
#Requires -Version 7.2
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SqlPath = Join-Path $PSScriptRoot 'seed-extensive.sql'

if (-not (Test-Path -LiteralPath $SqlPath)) {
    throw "No se encontró el archivo SQL: $SqlPath"
}

if (-not $NoBackup) {
    $backupDir = Join-Path $ProjectRoot '.main-instances\backups'
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $backupFile = Join-Path $backupDir "pre-extensive-seed-$stamp.dcbak"
    Write-Host "Respaldando base actual en $backupFile ..."
    Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force
    $dockerPath = (Get-Command docker -CommandType Application -ErrorAction Stop).Source
    $dump = [Diagnostics.ProcessStartInfo]::new($dockerPath)
    $dump.UseShellExecute = $false
    $dump.RedirectStandardOutput = $true
    $dump.RedirectStandardError = $true
    foreach ($argument in @('exec', $ContainerName, 'pg_dump', '--no-password', '--format=custom', '-U', $Username, '-d', $Database)) {
        $dump.ArgumentList.Add($argument)
    }
    Protect-BackupProcessOutput -StartInfo $dump -OutputPath $backupFile
}

Write-Host "Cargando base de datos extensa (últimos 30 días) en $ContainerName ..."
Get-Content -LiteralPath $SqlPath -Raw -Encoding UTF8 | docker exec -i $ContainerName psql -X -U $Username -d $Database -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "Falló la carga del script $SqlPath." }

Write-Host "Resumen de registros generados:"
docker exec $ContainerName psql -U $Username -d $Database -c "
SELECT 'Usuarios' AS entidad, count(*) AS total FROM identity.users
UNION ALL SELECT 'Vendedores', count(*) FROM seller.seller_profiles
UNION ALL SELECT 'Zonas', count(*) FROM customer.zones
UNION ALL SELECT 'Marcas', count(*) FROM catalog.brands
UNION ALL SELECT 'Categorías', count(*) FROM catalog.categories
UNION ALL SELECT 'Listas de precios', count(*) FROM catalog.price_lists
UNION ALL SELECT 'Productos', count(*) FROM catalog.products
UNION ALL SELECT 'Proveedores', count(*) FROM supplier.suppliers
UNION ALL SELECT 'Compras a proveedores', count(*) FROM purchasing.supplier_orders
UNION ALL SELECT 'Clientes (totales)', count(*) FROM customer.customers
UNION ALL SELECT 'Revendedores', count(*) FROM customer.customers WHERE kind = 'RESELLER'
UNION ALL SELECT 'Clientes de revendedores', count(*) FROM customer.customers WHERE reseller_id IS NOT NULL
UNION ALL SELECT 'Pedidos (últimos 30 días)', count(*) FROM orders.orders
UNION ALL SELECT 'Líneas de pedidos', count(*) FROM orders.order_items
UNION ALL SELECT 'Ventas', count(*) FROM sale.sales
UNION ALL SELECT 'Pagos', count(*) FROM payment.payments
UNION ALL SELECT 'Devoluciones', count(*) FROM sale.returns
UNION ALL SELECT 'Movimientos de stock', count(*) FROM inventory.stock_movements
UNION ALL SELECT 'Planes de comisión', count(*) FROM app.commission_plans
UNION ALL SELECT 'Comisiones devengadas', count(*) FROM sale.commission_entries
UNION ALL SELECT 'Liquidaciones de comisión', count(*) FROM sale.commission_settlements
UNION ALL SELECT 'Gastos', count(*) FROM expense.expenses
UNION ALL SELECT 'Eventos de auditoría', count(*) FROM audit.audit_events;
"
