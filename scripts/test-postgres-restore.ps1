[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SourceDatabase,
    [string]$PgHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$Port = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$Username = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } elseif ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'distribuidora' })
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -and [string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) {
    throw 'Defina DB_PASSWORD o POSTGRES_PASSWORD.'
}
$dbPassword = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { $env:POSTGRES_PASSWORD }
$pgBin = $env:PG_BIN
$createdb = if ($pgBin) { Join-Path $pgBin 'createdb.exe' } else { (Get-Command createdb -ErrorAction Stop).Source }
$dropdb = if ($pgBin) { Join-Path $pgBin 'dropdb.exe' } else { (Get-Command dropdb -ErrorAction Stop).Source }
$psql = if ($pgBin) { Join-Path $pgBin 'psql.exe' } else { (Get-Command psql -ErrorAction Stop).Source }
$root = Split-Path -Parent $PSScriptRoot
$temp = Join-Path ([IO.Path]::GetTempPath()) ("distribuidora-restore-test-" + [guid]::NewGuid())
$archive = Join-Path $temp 'source.dcbak'
$plain = Join-Path $temp 'source.dump'
$target = 'codex_restore_' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$tampered = Join-Path $temp 'tampered.dcbak'
$previousPassword = $env:PGPASSWORD
New-Item -ItemType Directory -Force -Path $temp | Out-Null
try {
    $env:PGPASSWORD = $dbPassword
    & (Join-Path $PSScriptRoot 'backup-postgres.ps1') -Database $SourceDatabase -PgHost $PgHost -Port $Port -Username $Username -OutputPath $archive -SkipRetention
    if (-not (Test-Path -LiteralPath $archive)) { throw 'La generación del backup de prueba falló.' }
    & $createdb --no-password "--host=$PgHost" "--port=$Port" "--username=$Username" "--owner=$Username" $target
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la base descartable para restauración.' }
    Copy-Item -LiteralPath $archive -Destination $tampered
    $tamperStream = [IO.File]::Open($tampered, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $tamperStream.Position = $tamperStream.Length - 1
        $last = $tamperStream.ReadByte()
        $tamperStream.Position = $tamperStream.Length - 1
        $tamperStream.WriteByte($last -bxor 1)
    } finally { $tamperStream.Dispose() }
    $tamperRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'restore-postgres.ps1') -BackupPath $tampered -TargetDatabase $target -PgHost $PgHost -Port $Port -Username $Username -Force
    } catch { $tamperRejected = $true }
    if (-not $tamperRejected) { throw 'El backup alterado no fue rechazado.' }
    $targetUntouched = & $psql --no-password -At "--host=$PgHost" "--port=$Port" "--username=$Username" "--dbname=$target" -c "select to_regclass('public.flyway_schema_history') is null"
    if ($LASTEXITCODE -ne 0 -or $targetUntouched.Trim() -ne 't') { throw 'El intento de restaurar un backup alterado modificó la base descartable.' }
    & (Join-Path $PSScriptRoot 'restore-postgres.ps1') -BackupPath $archive -TargetDatabase $target -PgHost $PgHost -Port $Port -Username $Username -Force
    $latest = & $psql --no-password -At "--host=$PgHost" "--port=$Port" "--username=$Username" "--dbname=$target" -c "select max(version::integer) from public.flyway_schema_history where success"
    if ($LASTEXITCODE -ne 0 -or [int]$latest -lt 1) { throw 'La base restaurada no contiene historial Flyway válido.' }
    $notificationTable = & $psql --no-password -At "--host=$PgHost" "--port=$Port" "--username=$Username" "--dbname=$target" -c "select to_regclass('notification.delivery_requests') is not null"
    if ($LASTEXITCODE -ne 0 -or $notificationTable.Trim() -ne 't') { throw 'La base restaurada no contiene el esquema de notificaciones esperado.' }
    Write-Output "Restauración verificada en la base descartable $target (Flyway V$latest)."
} finally {
    if ($previousPassword) { $env:PGPASSWORD = $previousPassword } else { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    & $dropdb --no-password --if-exists "--host=$PgHost" "--port=$Port" "--username=$Username" $target 2>$null
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
