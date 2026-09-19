param(
    [ValidateSet('front', 'back', 'both')]
    [string]$Mode = 'both'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Frontend = Join-Path $Root 'frontend'
$BackendPom = Join-Path $Root 'backend\pom.xml'

if (-not (Test-Path -LiteralPath $Frontend)) {
    throw "No se encontró la carpeta frontend: $Frontend"
}

if (-not (Test-Path -LiteralPath $BackendPom)) {
    throw "No se encontró el backend Maven: $BackendPom"
}

function Start-Frontend {
    Start-Process powershell.exe `
        -WorkingDirectory $Frontend `
        -ArgumentList @('-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', 'npm run dev')
}

function Start-Backend {
    Start-Process powershell.exe `
        -WorkingDirectory (Split-Path -Parent $BackendPom) `
        -ArgumentList @('-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', 'mvn spring-boot:run')
}

switch ($Mode) {
    'front' {
        Write-Host 'Iniciando frontend en una nueva ventana...'
        Start-Frontend
    }
    'back' {
        Write-Host 'Iniciando backend en una nueva ventana...'
        Start-Backend
    }
    'both' {
        Write-Host 'Iniciando frontend y backend en nuevas ventanas...'
        Start-Backend
        Start-Frontend
    }
}
