param(
    [ValidateSet('start', 'front', 'back', 'both')]
    [string]$Mode = 'start'
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

function Set-DevEnvironmentDefault([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, 'Process'))) {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
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
    'start' {
        if ([string]::IsNullOrWhiteSpace($env:DB_URL)) {
            Write-Host 'Iniciando PostgreSQL con Docker Compose y esperando que esté listo...'
            docker compose up -d --wait db
            if ($LASTEXITCODE -ne 0) {
                throw 'No se pudo iniciar PostgreSQL. Revisá Docker Desktop y docker compose.'
            }
            $composeConfig = docker compose config --format json | ConvertFrom-Json
            if ($LASTEXITCODE -ne 0) {
                throw 'No se pudo leer la configuración efectiva de Docker Compose.'
            }
            $database = $composeConfig.services.db.environment
            Set-DevEnvironmentDefault DB_URL "jdbc:postgresql://localhost:5433/$($database.POSTGRES_DB)"
            Set-DevEnvironmentDefault DB_USERNAME $database.POSTGRES_USER
            Set-DevEnvironmentDefault DB_PASSWORD $database.POSTGRES_PASSWORD
        }
        Set-DevEnvironmentDefault SPRING_PROFILES_ACTIVE 'local'
        if ($env:SPRING_PROFILES_ACTIVE -eq 'local') {
            Set-DevEnvironmentDefault SEED_DEMO 'true'
            Set-DevEnvironmentDefault DEMO_PASSWORD 'ChangeMe123!'
            Set-DevEnvironmentDefault ADMIN_EMAIL 'dev-admin@distribuidora.local'
            Set-DevEnvironmentDefault ADMIN_PASSWORD 'ChangeMe123!'
        }
        Write-Host 'PostgreSQL listo. Iniciando backend y frontend en ventanas separadas...'
        Start-Backend
        Start-Frontend
        Write-Host 'Frontend: http://localhost:5173 | Backend: http://localhost:8080'
    }
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
