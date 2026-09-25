[CmdletBinding()]
param(
    [string]$TaskName = 'Distribuidora PostgreSQL encrypted daily backup',
    [string]$PowerShell = 'pwsh.exe'
)

$scriptPath = Join-Path $PSScriptRoot 'backup-postgres.ps1'
$arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`""
$action = New-ScheduledTaskAction -Execute $PowerShell -Argument $arguments -WorkingDirectory (Split-Path -Parent $PSScriptRoot)
$trigger = New-ScheduledTaskTrigger -Daily -At 3:00AM
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 2)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Crea backup PostgreSQL custom-format, cifrado y con retención diaria/mensual.' -Force | Out-Null
Write-Output "Tarea registrada: $TaskName (diaria a las 03:00)."
