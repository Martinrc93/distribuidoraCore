[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$ExternalOutputDirectory,
    [string]$PgHost = '127.0.0.1',
    [int]$Port = 5432,
    [string]$Username = 'distribuidora',
    [string]$PgBin
)

$ErrorActionPreference = 'Stop'
if ($PgBin) { $env:PG_BIN = $PgBin }
$logDirectory = Join-Path $env:LOCALAPPDATA 'Distribuidora\Logs'
$null = New-Item -ItemType Directory -Force -Path $logDirectory
$logPath = $env:DISTRIBUIDORA_BACKUP_TASK_LOG
if ([string]::IsNullOrWhiteSpace($logPath)) {
    $runId = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'), [guid]::NewGuid().ToString('N').Substring(0, 8)
    $logPath = Join-Path $logDirectory "postgres-backup-task-$runId.log"
}
$env:DISTRIBUIDORA_BACKUP_TASK_LOG = $logPath
$exitCode = 0
Add-Content -LiteralPath $logPath -Value "[$(Get-Date -Format o)] Inicia ejecución programada de backup."
try {
    $output = & (Join-Path $PSScriptRoot 'backup-postgres.ps1') `
        -Database $Database -PgHost $PgHost -Port $Port -Username $Username `
        -OutputDirectory $OutputDirectory -ExternalOutputDirectory $ExternalOutputDirectory 2>&1
    foreach ($line in $output) { Add-Content -LiteralPath $logPath -Value ([string]$line) }
    Add-Content -LiteralPath $logPath -Value "[$(Get-Date -Format o)] Backup programado finalizado correctamente."
} catch {
    $exitCode = 1
    Add-Content -LiteralPath $logPath -Value "[$(Get-Date -Format o)] Falló el backup programado: $($_.Exception.Message)"
} finally {
    Add-Content -LiteralPath $logPath -Value "[$(Get-Date -Format o)] Fin de ejecución (código $exitCode)."
}
if ($exitCode -ne 0) { exit $exitCode }
