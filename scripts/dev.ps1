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
        Write-Host 'Iniciando PostgreSQL con Docker Compose y esperando que esté listo...'
        docker compose up -d --wait db
        if ($LASTEXITCODE -ne 0) {
            throw 'No se pudo iniciar PostgreSQL. Revisá Docker Desktop y docker compose.'
        }
        $composeConfig = docker compose config --format json | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) {
            throw 'No se pudo leer la configuración efectiva de Docker Compose.'
        }
        $database = $composeConfig.services.db.environment.POSTGRES_DB
        $dbUsername = $composeConfig.services.db.environment.POSTGRES_USER
        $dbPassword = $composeConfig.services.db.environment.POSTGRES_PASSWORD
        $quotedUsername = '"' + $dbUsername.Replace('"', '""') + '"'
        $quotedPassword = "'" + $dbPassword.Replace("'", "''") + "'"
        $passwordSyncSql = "ALTER ROLE $quotedUsername WITH PASSWORD $quotedPassword;"
        docker compose exec -T db psql -v ON_ERROR_STOP=1 -U $dbUsername -d $database -c $passwordSyncSql
        if ($LASTEXITCODE -ne 0) {
            throw 'No se pudo sincronizar la contraseña del usuario PostgreSQL con Docker Compose.'
        }
        $env:DB_URL = "jdbc:postgresql://localhost:5433/$database"
        $env:DB_USERNAME = $dbUsername
        $env:DB_PASSWORD = $dbPassword
        $env:SPRING_PROFILES_ACTIVE = 'local'
        $env:SEED_DEMO = 'true'
        $env:DEMO_PASSWORD = 'ChangeMe123!'
        $env:ADMIN_EMAIL = 'dev-admin@distribuidora.local'
        $env:ADMIN_PASSWORD = 'ChangeMe123!'
        Write-Host 'PostgreSQL listo. Iniciando backend y frontend en ventanas separadas...'
        Start-Backend
        Start-Frontend
        Write-Host 'Frontend: http://localhost:5173 | Backend: http://localhost:8080 | PostgreSQL Docker: localhost:5433'
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
