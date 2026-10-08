$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PreviousDatabaseEnvironment = @{}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
    $PreviousDatabaseEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

Push-Location $ProjectRoot
try {
    if ([string]::IsNullOrWhiteSpace($env:DB_URL)) {
        docker compose up -d --wait db
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar PostgreSQL.' }
        $composeJson = docker compose config --format json
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo leer la configuración de Docker Compose.' }
        $database = ($composeJson | ConvertFrom-Json).services.db.environment
        $env:DB_URL = "jdbc:postgresql://localhost:5433/$($database.POSTGRES_DB)"
        $env:DB_USERNAME = $database.POSTGRES_USER
        $env:DB_PASSWORD = $database.POSTGRES_PASSWORD
    }

    Write-Host 'Ejecutando la seed demo: ventas con fechas actuales y pedidos a proveedores del último mes.'
    $seedArguments = '--spring.profiles.active=local --app.seed-demo=true --refresh-demo-dates --app.seed-only=true --server.port=0 --app.outbox.worker.enabled=false --app.notifications.retention.enabled=false'
    # Keep compilation separate from any running backend's mapped class files.
    $seedBuildDirectory = Join-Path $ProjectRoot 'frontend\test-results\seed-runtime'
    & mvn -f (Join-Path $ProjectRoot 'backend\pom.xml') "-Disolated.build.dir=$seedBuildDirectory" spring-boot:run "-Dspring-boot.run.arguments=$seedArguments"
    if ($LASTEXITCODE -ne 0) { throw 'La ejecución de la seed falló.' }
}
finally {
    foreach ($name in $PreviousDatabaseEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $PreviousDatabaseEnvironment[$name], 'Process')
    }
    Pop-Location
}
