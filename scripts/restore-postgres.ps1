[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$BackupPath,
    [Parameter(Mandatory)][string]$TargetDatabase,
    [string]$PgHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$Port = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$Username = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } elseif ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'distribuidora' }),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $BackupPath -PathType Leaf)) { throw "No existe el backup: $BackupPath" }
$dbPassword = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { $env:POSTGRES_PASSWORD }
$pgBin = $env:PG_BIN
$pgRestore = if ($pgBin) { Join-Path $pgBin 'pg_restore.exe' } else { (Get-Command pg_restore -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $pgRestore)) { throw "No se encontró pg_restore: $pgRestore" }
Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force

$plainDump = Join-Path ([IO.Path]::GetTempPath()) ("restore-" + [guid]::NewGuid() + '.dump')
$previousPassword = $env:PGPASSWORD
try {
    $resolvedBackup = (Resolve-Path -LiteralPath $BackupPath).Path
    $null = Test-BackupArchive -InputPath $resolvedBackup
    Unprotect-BackupArchive -InputPath $resolvedBackup -OutputPath $plainDump
    if ($dbPassword) { $env:PGPASSWORD = $dbPassword }
    else { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    $list = & $pgRestore --list $plainDump 2>&1
    if ($LASTEXITCODE -ne 0 -or -not ($list -match 'TABLE')) { throw 'pg_restore rechazó el backup o no encontró objetos restaurables.' }
    $action = "Restaurar backup sobre PostgreSQL $PgHost`:$Port/$TargetDatabase (reemplaza objetos existentes)"
    if ($Force -or $PSCmdlet.ShouldProcess($TargetDatabase, $action)) {
        $restoreArgs = @('--no-password', '--exit-on-error', '--clean', '--if-exists', '--no-owner', "--host=$PgHost", "--port=$Port", "--username=$Username", "--dbname=$TargetDatabase", $plainDump)
        & $pgRestore @restoreArgs
        if ($LASTEXITCODE -ne 0) { throw "pg_restore falló con código $LASTEXITCODE." }
    }
} finally {
    if ($previousPassword) { $env:PGPASSWORD = $previousPassword } else { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $plainDump -Force -ErrorAction SilentlyContinue
}
