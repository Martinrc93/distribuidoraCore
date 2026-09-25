[CmdletBinding()]
param(
    [string]$TaskName = 'Distribuidora PostgreSQL encrypted daily backup',
    [string]$PowerShell = 'pwsh.exe',
    [string]$Database = 'distribuidora',
    [string]$PgHost = '127.0.0.1',
    [int]$Port = 5432,
    [string]$Username = 'distribuidora',
    [string]$PgBin,
    [string]$OutputDirectory = (Join-Path $env:LOCALAPPDATA 'Distribuidora\Backups'),
    [Parameter(Mandatory)][string]$ExternalOutputDirectory,
    [datetime]$RunAt = [datetime]::Today.AddHours(3)
)

$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot 'run-postgres-backup-task.ps1'
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    throw "No se encontró el wrapper de backup: $runnerPath"
}

if ([IO.Path]::IsPathRooted($PowerShell)) {
    $powerShellPath = $PowerShell
} else {
    $powerShellPath = (Get-Command -Name $PowerShell -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Source
}
if (-not (Test-Path -LiteralPath $powerShellPath -PathType Leaf)) {
    throw "No se encontró PowerShell 7 en la ruta resuelta: $powerShellPath"
}

function ConvertTo-PowerShellLiteral {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) { $Value = '' }
    return "'" + $Value.Replace("'", "''") + "'"
}

# Pass no script arguments through Task Scheduler: its argv handling caused
# pwsh to exit before PowerShell entered the wrapper. An encoded command keeps
# the invocation as one native argument, then PowerShell binds the parameters.
$invocation = '& ' + (ConvertTo-PowerShellLiteral $runnerPath) +
    ' -Database ' + (ConvertTo-PowerShellLiteral $Database) +
    ' -PgHost ' + (ConvertTo-PowerShellLiteral $PgHost) +
    ' -Port ' + $Port +
    ' -Username ' + (ConvertTo-PowerShellLiteral $Username) +
    ' -PgBin ' + (ConvertTo-PowerShellLiteral $PgBin) +
    ' -OutputDirectory ' + (ConvertTo-PowerShellLiteral $OutputDirectory) +
    ' -ExternalOutputDirectory ' + (ConvertTo-PowerShellLiteral $ExternalOutputDirectory)
$encodedInvocation = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($invocation))
$arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encodedInvocation"
$action = New-ScheduledTaskAction -Execute $powerShellPath -Argument $arguments
$trigger = New-ScheduledTaskTrigger -Daily -At $RunAt
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Hours 2) -MultipleInstances IgnoreNew
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Description 'Crea backup PostgreSQL cifrado y replica a la carpeta externa configurada.' -Force |
    Out-Null

Write-Output "Tarea registrada: $TaskName (diaria a las $($RunAt.ToString('HH:mm')); identidad $identity)."
Write-Output "Ejecutable: $powerShellPath"
Write-Output 'La configuración se transporta en la acción codificada; no incluye credenciales.'
