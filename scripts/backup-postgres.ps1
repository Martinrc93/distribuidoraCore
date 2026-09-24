[CmdletBinding()]
param(
    [string]$Database = $(if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'distribuidora' }),
    [string]$PgHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$Port = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$Username = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } elseif ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'distribuidora' }),
    [string]$OutputPath,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\backups'),
    [switch]$SkipRetention
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force

if (-not $OutputPath) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputPath = Join-Path $OutputDirectory "$Database-$timestamp.dcbak"
}
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -and [string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) {
    throw 'Defina DB_PASSWORD o POSTGRES_PASSWORD; la contraseña no se pasa por argumentos.'
}
$dbPassword = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { $env:POSTGRES_PASSWORD }
$pgBin = $env:PG_BIN
$pgDump = if ($pgBin) { Join-Path $pgBin 'pg_dump.exe' } else { (Get-Command pg_dump -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $pgDump)) { throw "No se encontró pg_dump: $pgDump" }

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
$previousPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $dbPassword
    $start = [Diagnostics.ProcessStartInfo]::new($pgDump)
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($arg in @('--no-password', '--format=custom', "--host=$PgHost", "--port=$Port", "--username=$Username", "--dbname=$Database")) {
        $start.ArgumentList.Add($arg)
    }
    Protect-BackupProcessOutput -StartInfo $start -OutputPath $OutputPath
    Write-Output (Resolve-Path -LiteralPath $OutputPath).Path
} finally {
    if ($previousPassword) { $env:PGPASSWORD = $previousPassword } else { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}

if (-not $SkipRetention) {
    $files = @(Get-ChildItem -LiteralPath (Split-Path -Parent $OutputPath) -Filter '*.dcbak' -File | Sort-Object LastWriteTime -Descending)
    $keep = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $dailyCutoff = (Get-Date).Date.AddDays(-1)
    $files | Where-Object LastWriteTime -ge $dailyCutoff | Group-Object { $_.LastWriteTime.ToString('yyyy-MM-dd') } |
        ForEach-Object { $null = $keep.Add($_.Group[0].FullName) }
    $monthlyCutoff = (Get-Date -Day 1).Date.AddMonths(-11)
    $files | Where-Object LastWriteTime -ge $monthlyCutoff | Group-Object { $_.LastWriteTime.ToString('yyyy-MM') } |
        ForEach-Object { $null = $keep.Add($_.Group[0].FullName) }
    foreach ($file in $files) {
        if (-not $keep.Contains($file.FullName)) { Remove-Item -LiteralPath $file.FullName -Force }
    }
}
