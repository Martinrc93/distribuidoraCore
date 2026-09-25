[CmdletBinding()]
param(
    [string]$StorePath = (Join-Path $env:LOCALAPPDATA 'Distribuidora\postgres-backup-key.dpapi')
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force
$result = Initialize-BackupEncryptionKeyStore -StorePath $StorePath
Write-Output "Clave DPAPI creada para la cuenta Windows actual. ID: $($result.KeyId)"
Write-Output "Almacén: $($result.Path)"
Write-Warning 'DPAPI vincula la clave a este usuario y equipo. Guarde una copia de recuperación en un gestor de secretos independiente antes de depender de estos backups ante pérdida del host.'
