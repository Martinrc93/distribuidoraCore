param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DbName = $(if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "distribuidora" }),
    [string]$DbUser = $(if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "distribuidora" }),
    [string]$AdminEmail = $(if ($env:ADMIN_EMAIL) { $env:ADMIN_EMAIL } else { "admin1@distribuidora.local" }),
    [string]$AdminPassword = $(if ($env:ADMIN_PASSWORD) { $env:ADMIN_PASSWORD } else { "ChangeMe123!" })
)

$ErrorActionPreference = "Stop"

function Invoke-DbQuery([string]$Query) {
    $result = @(docker compose exec -T db psql -U $DbUser -d $DbName -At -c $Query)
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL query failed: $Query"
    }
    return ($result -join "`n").Trim()
}

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "$Message. Expected [$Expected], got [$Actual]."
    }
}

Write-Host "Starting Compose PostgreSQL and backend without removing the persistent volume..."
docker compose up -d --build db backend
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed." }

$health = $null
for ($attempt = 1; $attempt -le 30; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get
        if ($health.status -eq "UP") { break }
    } catch {
        if ($attempt -eq 30) { throw "Backend health did not become available: $($_.Exception.Message)" }
    }
    Start-Sleep -Seconds 2
}
Assert-Equal "UP" $health.status "Backend health is not UP"

$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType "application/json" -Body (@{
    email = $AdminEmail
    password = $AdminPassword
} | ConvertTo-Json)
$token = $login.accessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw "Login did not return an access token." }

$seed = Invoke-DbQuery @"
SELECT c.id || '|' || p.id
FROM customer.customers c
CROSS JOIN catalog.products p
JOIN inventory.inventory_balances b ON b.product_id = p.id
WHERE c.status = 'ACTIVE' AND p.status = 'ACTIVE' AND b.quantity >= 1
ORDER BY c.id, p.id
LIMIT 1
"@
if ([string]::IsNullOrWhiteSpace($seed)) { throw "No active customer/product with available stock was found." }
$seedParts = $seed.Split('|')
$customerId = $seedParts[0]
$productId = $seedParts[1]
$headers = @{ Authorization = "Bearer $token" }
$resolvedPrice = Invoke-RestMethod -Uri "$BaseUrl/api/pricing/resolve?customerId=$customerId&productId=$productId" -Method Get -Headers $headers
$total = [decimal]$resolvedPrice.unitPrice
$cash = [math]::Round($total / 2, 4)
$account = $total - $cash
$key = "smoke-$(Get-Date -Format 'yyyyMMddHHmmssfff')"

$before = Invoke-DbQuery @"
SELECT
  (SELECT count(*) FROM orders.orders),
  (SELECT count(*) FROM sale.sales),
  (SELECT count(*) FROM orders.order_items),
  (SELECT count(*) FROM sale.sale_items),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM payment.payments),
  (SELECT count(*) FROM customer.account_ledger),
  (SELECT balance FROM customer.customers WHERE id = '$customerId')
"@
$beforeParts = $before.Split('|')

$payload = @{
    idempotencyKey = $key
    customerId = $customerId
    priceListId = $null
    lines = @(@{
        productId = $productId
        quantity = 1.0
        lineDiscountPercent = 0.0
        unitPriceOverride = $null
    })
    orderDiscountPercent = 0.0
    payments = @(
        @{ method = "CASH"; amount = $cash },
        @{ method = "CUSTOMER_ACCOUNT"; amount = $account }
    )
} | ConvertTo-Json -Depth 6

$rollbackBefore = Invoke-DbQuery @"
SELECT
  (SELECT count(*) FROM orders.orders),
  (SELECT count(*) FROM sale.sales),
  (SELECT count(*) FROM orders.order_items),
  (SELECT count(*) FROM sale.sale_items),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM payment.payments),
  (SELECT count(*) FROM customer.account_ledger),
  (SELECT balance FROM customer.customers WHERE id = '$customerId'),
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId')
"@
$null = Invoke-DbQuery @"
DROP TRIGGER IF EXISTS smoke_force_order_failure ON orders.orders;
CREATE OR REPLACE FUNCTION smoke_force_order_failure()
RETURNS trigger
LANGUAGE plpgsql
AS `$function`$
BEGIN
  IF NEW.idempotency_key LIKE 'rollback-%' THEN
    RAISE EXCEPTION 'deterministic smoke rollback failure after order write';
  END IF;
  RETURN NEW;
END;
`$function`$;
CREATE TRIGGER smoke_force_order_failure
AFTER INSERT ON orders.orders
FOR EACH ROW EXECUTE FUNCTION smoke_force_order_failure();
"@
$rollbackPayload = @{
    idempotencyKey = "rollback-$key"
    customerId = $customerId
    priceListId = $null
    lines = @(@{
        productId = $productId
        quantity = 1.0
        lineDiscountPercent = 0.0
        unitPriceOverride = $null
    })
    orderDiscountPercent = 0.0
    payments = @(@{ method = "CASH"; amount = $total })
} | ConvertTo-Json -Depth 6
$rollbackFailed = $false
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/orders/confirm" -Method Post -Headers $headers -ContentType "application/json" -Body $rollbackPayload | Out-Null
} catch {
    $rollbackFailed = $true
}
if (-not $rollbackFailed) { throw "Expected post-write database failure to roll back." }
$rollbackAfter = Invoke-DbQuery @"
SELECT
  (SELECT count(*) FROM orders.orders),
  (SELECT count(*) FROM sale.sales),
  (SELECT count(*) FROM orders.order_items),
  (SELECT count(*) FROM sale.sale_items),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM payment.payments),
  (SELECT count(*) FROM customer.account_ledger),
  (SELECT balance FROM customer.customers WHERE id = '$customerId'),
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId')
"@
$rollbackBeforeParts = $rollbackBefore.Split('|')
$rollbackAfterParts = $rollbackAfter.Split('|')
for ($index = 0; $index -lt 7; $index++) {
    Assert-Equal ([int]$rollbackBeforeParts[$index]) ([int]$rollbackAfterParts[$index]) "Rollback changed persisted count index $index"
}
Assert-Equal ([decimal]$rollbackBeforeParts[7]) ([decimal]$rollbackAfterParts[7]) "Rollback changed customer balance"
Assert-Equal ([decimal]$rollbackBeforeParts[8]) ([decimal]$rollbackAfterParts[8]) "Rollback changed product stock"
$null = Invoke-DbQuery @"
DROP TRIGGER IF EXISTS smoke_force_order_failure ON orders.orders;
DROP FUNCTION IF EXISTS smoke_force_order_failure();
"@

$jobs = @(
    Start-Job -ScriptBlock {
        param($Uri, $Headers, $Body)
        Invoke-RestMethod -Uri $Uri -Method Post -Headers $Headers -ContentType "application/json" -Body $Body
    } -ArgumentList "$BaseUrl/api/orders/confirm", $headers, $payload
    Start-Job -ScriptBlock {
        param($Uri, $Headers, $Body)
        Invoke-RestMethod -Uri $Uri -Method Post -Headers $Headers -ContentType "application/json" -Body $Body
    } -ArgumentList "$BaseUrl/api/orders/confirm", $headers, $payload
)
Wait-Job -Job $jobs | Out-Null
$responses = @($jobs | Receive-Job -ErrorAction Stop)
$jobs | Remove-Job -Force
if ($responses.Count -ne 2) { throw "Expected two concurrent confirmation responses." }
$first = $responses[0]
$second = $responses[1]
if ([string]::IsNullOrWhiteSpace($first.orderId) -or [string]::IsNullOrWhiteSpace($first.saleId)) {
    throw "Confirmation did not return orderId and saleId."
}
foreach ($property in @("orderId", "saleId", "orderNumber", "saleNumber", "total", "paid", "balance")) {
    Assert-Equal ([string]$first.$property) ([string]$second.$property) "Concurrent responses differed in $property"
}
Assert-Equal ([decimal]$total) ([decimal]$first.total) "Unexpected confirmation total"
Assert-Equal $cash ([decimal]$first.paid) "Unexpected confirmation paid amount"
Assert-Equal $account ([decimal]$first.balance) "Unexpected confirmation balance"

$afterCreate = Invoke-DbQuery @"
SELECT
  (SELECT count(*) FROM orders.orders),
  (SELECT count(*) FROM sale.sales),
  (SELECT count(*) FROM orders.order_items),
  (SELECT count(*) FROM sale.sale_items),
   (SELECT count(*) FROM inventory.stock_movements),
   (SELECT count(*) FROM payment.payments),
   (SELECT count(*) FROM customer.account_ledger),
  (SELECT balance FROM customer.customers WHERE id = '$customerId')
"@
$createdParts = $afterCreate.Split('|')
Assert-Equal ([int]$beforeParts[0] + 1) ([int]$createdParts[0]) "Order count did not increase by one"
Assert-Equal ([int]$beforeParts[1] + 1) ([int]$createdParts[1]) "Sale count did not increase by one"
Assert-Equal ([int]$beforeParts[2] + 1) ([int]$createdParts[2]) "Order item count did not increase by one"
Assert-Equal ([int]$beforeParts[3] + 1) ([int]$createdParts[3]) "Sale item count did not increase by one"
Assert-Equal ([int]$beforeParts[4] + 1) ([int]$createdParts[4]) "Concurrent confirmations changed SALE movement count more than once"
Assert-Equal ([int]$beforeParts[5] + 1) ([int]$createdParts[5]) "Concurrent confirmations changed payment count more than once"
Assert-Equal ([int]$beforeParts[6] + 1) ([int]$createdParts[6]) "Concurrent confirmations changed ledger count more than once"
Assert-Equal ([decimal]$beforeParts[7] + $account) ([decimal]$createdParts[7]) "Customer balance did not increase by account amount"

Write-Host "SMOKE PASS: pricing resolve, rollback, concurrent idempotent order, sale, items, SALE movement, payment, ledger, balance, and persistent-volume safety verified."
