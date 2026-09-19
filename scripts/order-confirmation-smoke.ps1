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

function Assert-HttpStatus([string]$Uri, [string]$Method, $Headers, [string]$Body, [int]$ExpectedStatus, [string]$Message) {
    try {
        $request = @{ Uri = $Uri; Method = $Method; Headers = $Headers }
        if ($null -ne $Body) {
            $request.ContentType = "application/json"
            $request.Body = $Body
        }
        $response = Invoke-WebRequest @request -UseBasicParsing
        $status = [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response -eq $null) { throw }
        $status = [int]$_.Exception.Response.StatusCode
    }
    Assert-Equal $ExpectedStatus $status $Message
}

Write-Host "Starting Compose PostgreSQL, backend, and frontend without removing the persistent volume..."
docker compose up -d --build db backend frontend
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
WHERE c.status = 'ACTIVE' AND p.status = 'ACTIVE' AND b.quantity >= 3
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

$lifecycleTotal = [decimal]$total
$lifecycleQuantity = 1.0

$failedPayload = @{
    idempotencyKey = "failed-$key"
    customerId = $customerId
    priceListId = $null
    lines = @(@{ productId = $productId; quantity = $lifecycleQuantity; lineDiscountPercent = 0.0; unitPriceOverride = $null })
    orderDiscountPercent = 0.0
    payments = @(@{ method = "CASH"; amount = $lifecycleTotal })
} | ConvertTo-Json -Depth 6
$failedConfirmation = Invoke-RestMethod -Uri "$BaseUrl/api/orders/confirm" -Method Post -Headers $headers -ContentType "application/json" -Body $failedPayload
$failedUri = "$BaseUrl/api/orders/$($failedConfirmation.orderId)/delivery-attempts"
$failedBody = @{ result = "FAILED"; observation = "Smoke delivery failure" } | ConvertTo-Json
Assert-HttpStatus $failedUri "Post" $headers $failedBody 204 "Recording the first FAILED attempt did not return 204"
$failedState = Invoke-DbQuery "SELECT o.status || '|' || s.status || '|' || da.result FROM orders.orders o JOIN sale.sales s ON s.order_id = o.id JOIN orders.delivery_attempts da ON da.order_id = o.id WHERE o.id = '$($failedConfirmation.orderId)'"
Assert-Equal "CONFIRMED|CONFIRMED|FAILED" $failedState "FAILED changed the order lifecycle"
Assert-HttpStatus $failedUri "Post" $headers (@{ result = "FAILED"; observation = "Smoke delivery retry" } | ConvertTo-Json) 204 "Recording a second FAILED attempt did not return 204"
$failedRetryState = Invoke-DbQuery "SELECT o.status || '|' || s.status || '|' || count(da.id) FROM orders.orders o JOIN sale.sales s ON s.order_id = o.id LEFT JOIN orders.delivery_attempts da ON da.order_id = o.id WHERE o.id = '$($failedConfirmation.orderId)' GROUP BY o.status, s.status"
Assert-Equal "CONFIRMED|CONFIRMED|2" $failedRetryState "A FAILED delivery was not retryable"

$deliveredPayload = @{
    idempotencyKey = "delivered-$key"
    customerId = $customerId
    priceListId = $null
    lines = @(@{ productId = $productId; quantity = $lifecycleQuantity; lineDiscountPercent = 0.0; unitPriceOverride = $null })
    orderDiscountPercent = 0.0
    payments = @(@{ method = "CASH"; amount = $lifecycleTotal })
} | ConvertTo-Json -Depth 6
$deliveredConfirmation = Invoke-RestMethod -Uri "$BaseUrl/api/orders/confirm" -Method Post -Headers $headers -ContentType "application/json" -Body $deliveredPayload
$deliveredUri = "$BaseUrl/api/orders/$($deliveredConfirmation.orderId)/delivery-attempts"
$deliveredBefore = Invoke-DbQuery @"
SELECT
  (SELECT status FROM orders.orders WHERE id = '$($deliveredConfirmation.orderId)'),
  (SELECT status FROM sale.sales WHERE order_id = '$($deliveredConfirmation.orderId)'),
  (SELECT count(*) FROM orders.delivery_attempts WHERE order_id = '$($deliveredConfirmation.orderId)'),
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId'),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM payment.payments),
  (SELECT count(*) FROM customer.account_ledger),
  (SELECT balance FROM customer.customers WHERE id = '$customerId')
"@
Assert-HttpStatus $deliveredUri "Post" $headers (@{ result = "DELIVERED" } | ConvertTo-Json) 204 "Recording DELIVERED did not return 204"
$deliveredState = Invoke-DbQuery "SELECT o.status || '|' || s.status FROM orders.orders o JOIN sale.sales s ON s.order_id = o.id WHERE o.id = '$($deliveredConfirmation.orderId)'"
Assert-Equal "DELIVERED|DELIVERED" $deliveredState "DELIVERED did not update order and sale together"
$deliveredAfter = Invoke-DbQuery @"
SELECT
  (SELECT status FROM orders.orders WHERE id = '$($deliveredConfirmation.orderId)'),
  (SELECT status FROM sale.sales WHERE order_id = '$($deliveredConfirmation.orderId)'),
  (SELECT count(*) FROM orders.delivery_attempts WHERE order_id = '$($deliveredConfirmation.orderId)'),
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId'),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM payment.payments),
  (SELECT count(*) FROM customer.account_ledger),
  (SELECT balance FROM customer.customers WHERE id = '$customerId')
"@
$deliveredBeforeParts = $deliveredBefore.Split('|')
$deliveredAfterParts = $deliveredAfter.Split('|')
Assert-Equal "CONFIRMED" $deliveredBeforeParts[0] "DELIVERED precondition changed the order status"
Assert-Equal "CONFIRMED" $deliveredBeforeParts[1] "DELIVERED precondition changed the sale status"
Assert-Equal 0 ([int]$deliveredBeforeParts[2]) "DELIVERED precondition already had delivery attempts"
Assert-Equal "DELIVERED" $deliveredAfterParts[0] "DELIVERED did not update the order"
Assert-Equal "DELIVERED" $deliveredAfterParts[1] "DELIVERED did not update the sale"
Assert-Equal 1 ([int]$deliveredAfterParts[2]) "DELIVERED did not persist one delivery attempt"
for ($index = 3; $index -lt $deliveredBeforeParts.Count; $index++) {
    Assert-Equal $deliveredBeforeParts[$index] $deliveredAfterParts[$index] "DELIVERED changed persisted value index $index"
}
Assert-HttpStatus $deliveredUri "Post" $headers (@{ result = "FAILED"; observation = "Terminal retry" } | ConvertTo-Json) 409 "Retrying a delivered order was not rejected"

$cancelPayload = @{
    idempotencyKey = "cancel-$key"
    customerId = $customerId
    priceListId = $null
    lines = @(@{ productId = $productId; quantity = $lifecycleQuantity; lineDiscountPercent = 0.0; unitPriceOverride = $null })
    orderDiscountPercent = 0.0
    payments = @()
} | ConvertTo-Json -Depth 6
$cancelBefore = Invoke-DbQuery @"
SELECT
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId'),
  (SELECT balance FROM customer.customers WHERE id = '$customerId'),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM customer.account_ledger)
"@
$cancelConfirmation = Invoke-RestMethod -Uri "$BaseUrl/api/orders/confirm" -Method Post -Headers $headers -ContentType "application/json" -Body $cancelPayload
$cancelAfterConfirm = Invoke-DbQuery @"
SELECT
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId'),
  (SELECT balance FROM customer.customers WHERE id = '$customerId'),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM customer.account_ledger)
"@
$cancelUri = "$BaseUrl/api/orders/$($cancelConfirmation.orderId)/cancel"
Assert-HttpStatus $cancelUri "Post" $headers $null 204 "Cancelling a confirmed order did not return 204"
$cancelAfter = Invoke-DbQuery @"
SELECT
  (SELECT o.status FROM orders.orders o WHERE o.id = '$($cancelConfirmation.orderId)'),
  (SELECT s.status FROM sale.sales s WHERE s.order_id = '$($cancelConfirmation.orderId)'),
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId'),
  (SELECT balance FROM customer.customers WHERE id = '$customerId'),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM payment.payments),
  (SELECT count(*) FROM customer.account_ledger),
  (SELECT count(*) FROM inventory.stock_movements WHERE reference_id = '$($cancelConfirmation.orderId)' AND movement_type = 'SALE'),
  (SELECT count(*) FROM inventory.stock_movements WHERE reference_id = '$($cancelConfirmation.orderId)' AND movement_type = 'SALE_CANCELLATION'),
  (SELECT count(*) FROM customer.account_ledger WHERE sale_id = '$($cancelConfirmation.saleId)' AND entry_type = 'CREDIT')
"@
$cancelBeforeParts = $cancelBefore.Split('|')
$cancelAfterConfirmParts = $cancelAfterConfirm.Split('|')
$cancelAfterParts = $cancelAfter.Split('|')
Assert-Equal ([decimal]$cancelBeforeParts[0] - $lifecycleQuantity) ([decimal]$cancelAfterConfirmParts[0]) "Cancellation confirmation did not reserve stock"
Assert-Equal ([decimal]$cancelBeforeParts[1] + $lifecycleTotal) ([decimal]$cancelAfterConfirmParts[1]) "Cancellation confirmation did not create account debt"
Assert-Equal "CANCELLED" $cancelAfterParts[0] "Cancellation did not update the order"
Assert-Equal "CANCELLED" $cancelAfterParts[1] "Cancellation did not update the sale"
Assert-Equal ([decimal]$cancelBeforeParts[0]) ([decimal]$cancelAfterParts[2]) "Cancellation did not restore stock"
Assert-Equal ([decimal]$cancelBeforeParts[1]) ([decimal]$cancelAfterParts[3]) "Cancellation did not restore customer balance"
Assert-Equal ([int]$cancelAfterConfirmParts[2] + 1) ([int]$cancelAfterParts[4]) "Cancellation did not add one stock reversal"
Assert-Equal ([int]$cancelAfterConfirmParts[3] + 1) ([int]$cancelAfterParts[6]) "Cancellation did not add the credit ledger entry"
Assert-Equal 1 ([int]$cancelAfterParts[7]) "Cancellation SALE movement count was unexpected"
Assert-Equal 1 ([int]$cancelAfterParts[8]) "Cancellation SALE_CANCELLATION movement count was unexpected"
Assert-Equal 1 ([int]$cancelAfterParts[9]) "Cancellation CREDIT ledger count was unexpected"
$cancelRetryBefore = $cancelAfter
Assert-HttpStatus $cancelUri "Post" $headers $null 409 "Retrying a cancelled order was not rejected"
$cancelRetryAfter = Invoke-DbQuery @"
SELECT
  (SELECT status FROM orders.orders WHERE id = '$($cancelConfirmation.orderId)'),
  (SELECT status FROM sale.sales WHERE order_id = '$($cancelConfirmation.orderId)'),
  (SELECT quantity FROM inventory.inventory_balances WHERE product_id = '$productId'),
  (SELECT balance FROM customer.customers WHERE id = '$customerId'),
  (SELECT count(*) FROM inventory.stock_movements),
  (SELECT count(*) FROM payment.payments),
  (SELECT count(*) FROM customer.account_ledger),
  (SELECT count(*) FROM inventory.stock_movements WHERE reference_id = '$($cancelConfirmation.orderId)' AND movement_type = 'SALE'),
  (SELECT count(*) FROM inventory.stock_movements WHERE reference_id = '$($cancelConfirmation.orderId)' AND movement_type = 'SALE_CANCELLATION'),
  (SELECT count(*) FROM customer.account_ledger WHERE sale_id = '$($cancelConfirmation.saleId)' AND entry_type = 'CREDIT')
"@
$cancelRetryBeforeParts = $cancelRetryBefore.Split('|')
$cancelRetryAfterParts = $cancelRetryAfter.Split('|')
for ($index = 0; $index -lt $cancelRetryBeforeParts.Count; $index++) {
    Assert-Equal $cancelRetryBeforeParts[$index] $cancelRetryAfterParts[$index] "Cancelled retry changed persisted value index $index"
}

Write-Host "Restarting backend and frontend without removing the persistent volume..."
docker compose restart backend frontend
if ($LASTEXITCODE -ne 0) { throw "docker compose restart failed." }
for ($attempt = 1; $attempt -le 30; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get
        if ($health.status -eq "UP") { break }
    } catch {
        if ($attempt -eq 30) { throw "Backend health did not recover after restart: $($_.Exception.Message)" }
    }
    Start-Sleep -Seconds 2
}
Assert-Equal "UP" $health.status "Backend health is not UP after restart"
$restartLogin = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType "application/json" -Body (@{
    email = $AdminEmail
    password = $AdminPassword
} | ConvertTo-Json)
$restartHeaders = @{ Authorization = "Bearer $($restartLogin.accessToken)" }
$persistedLifecycle = Invoke-RestMethod -Uri "$BaseUrl/api/orders/$($cancelConfirmation.orderId)" -Method Get -Headers $restartHeaders
Assert-Equal $cancelConfirmation.orderId $persistedLifecycle.order.id "Cancelled lifecycle record did not survive restart"
Assert-Equal "CANCELLED" $persistedLifecycle.order.status "Persisted lifecycle record changed after restart"
Assert-Equal "CANCELLED" $persistedLifecycle.sale.status "Persisted sale lifecycle record changed after restart"

Write-Host "SMOKE PASS: confirmation rollback/idempotency, retryable FAILED attempts, DELIVERED invariants, cancellation stock/ledger reversal, terminal retries, restart persistence, and persistent-volume safety verified."
