#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Reset', 'Load', 'Sample')][string]$Mode,
    [ValidateSet('Local', 'Support3200')][string]$Target = 'Local',
    [string]$AdminEmail = 'admin@distribuidora.local',
    [securestring]$AdminPassword,
    [string]$DataPath,
    [switch]$ExampleData,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../output/support-onboarding'),
    [switch]$ConfirmReset
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$evidence = Join-Path $output $runId
$worker = Join-Path $root 'frontend/scripts/support-onboarding.mjs'
$project = 'distribuidora'
$composeFile = Join-Path $root 'compose.yaml'
$docker = (Get-Command docker -CommandType Application | Select-Object -First 1).Source
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
$previousEnvironment = @{}
$runLock = $null
$demoOverride = $null
$demoStarted = $false

function Compose {
    & $docker compose @compose @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose falló (código $LASTEXITCODE)." }
}

function Invoke-Docker {
    & $docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker falló (código $LASTEXITCODE)." }
}

function Wait-Healthy([string]$Container) {
    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    do {
        $state = ((Invoke-Docker inspect $Container | ConvertFrom-Json)[0]).State
        if (!$state.Running) { throw "El contenedor $Container se detuvo durante el arranque." }
        if ($state.Health.Status -eq 'healthy') { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "El contenedor $Container no quedó listo a tiempo."
}

function Start-Application {
    if ($Target -eq 'Support3200') {
        foreach ($service in @('backend', 'frontend')) {
            Invoke-Docker start $containers[$service].Id | Out-Null
            Wait-Healthy $containers[$service].Id
        }
    } else { Compose up -d --wait --no-build --wait-timeout 120 }
}

function Stop-Application {
    if ($Target -eq 'Support3200') { Invoke-Docker stop $containers.frontend.Id $containers.backend.Id | Out-Null }
    else { Compose stop frontend backend }
}

function Get-ContainerEnvironment($Container) {
    $values = @{}
    foreach ($entry in $Container.Config.Env) {
        $parts = $entry.Split('=', 2)
        $values[$parts[0]] = $parts[1]
    }
    return $values
}

function Invoke-Sql([string]$Database, [string]$Statement) {
    $sqlArgs = @('psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', $dbUser, '-d', $Database, '-c', $Statement)
    if ($Target -eq 'Support3200') { Invoke-Docker exec -e PGCLIENTENCODING=UTF8 $containers.db.Id @sqlArgs }
    else { Compose exec -T -e PGCLIENTENCODING=UTF8 db @sqlArgs }
}

function Set-RunEnvironment([string]$Name, [string]$Value) {
    if (!$previousEnvironment.ContainsKey($Name)) {
        $previousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Invoke-Capture([string]$Action, [string]$Folder) {
    $binding = if ($Target -eq 'Support3200') { [string](Invoke-Docker port $containers.frontend.Id '80/tcp') }
               else { [string](Compose port frontend 8080) }
    if ($binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'El frontend debe estar publicado únicamente en 127.0.0.1.' }
    $secret = [Net.NetworkCredential]::new('', $AdminPassword).Password
    try {
        @{ url = "http://$binding"; email = $AdminEmail; password = $secret; dataPath = $DataPath;
           output = $Folder; sample = ($Mode -eq 'Sample' -or $ExampleData); action = $Action } |
            ConvertTo-Json -Compress | & $node $worker
        if ($LASTEXITCODE -ne 0) { throw "Falló la etapa $Action. Revisá el mensaje y las capturas guardadas." }
    } finally { $secret = $null }
}

function Disable-LocalDemo {
    $path = Join-Path $root '.env'
    $current = if ([IO.File]::Exists($path)) { [IO.File]::ReadAllText($path) } else { '' }
    if ($current -match '(?m)^SEED_DEMO=false\r?\n?\z') { return }
    if ($current -and !$current.EndsWith("`n")) { $current += [Environment]::NewLine }
    $temporary = "$path.$runId.tmp"
    try {
        [IO.File]::WriteAllText($temporary, $current + 'SEED_DEMO=false' + [Environment]::NewLine)
        [IO.File]::Move($temporary, $path, $true)
    } finally { [IO.File]::Delete($temporary) }
}

function Backup-Database {
    Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force
    $backups = if ($Mode -eq 'Sample') { $evidence } else { Join-Path $root 'backups' }
    [IO.Directory]::CreateDirectory($backups) | Out-Null
    $prefix = if ($Target -eq 'Support3200') { $project } else { $database }
    $path = Join-Path $backups "$prefix-before-onboarding-$runId.dcbak"
    $start = [Diagnostics.ProcessStartInfo]::new($docker)
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $execArgs = if ($Target -eq 'Support3200') { @('exec', $containers.db.Id) } else { @('compose') + $compose + @('exec', '-T', 'db') }
    foreach ($argument in ($execArgs + @('pg_dump', '--no-password', '--format=custom', '-U', $dbUser, '-d', $database))) {
        $start.ArgumentList.Add($argument)
    }
    Protect-BackupProcessOutput -StartInfo $start -OutputPath $path
    $null = Test-BackupArchive -InputPath $path
    if ($Mode -eq 'Sample') { [IO.File]::Delete($path) }
    else { Write-Host "Respaldo cifrado verificado en $path" }
}

function Reset-Database {
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
    $candidate = "onboarding_new_$suffix"
    $previous = "onboarding_old_$suffix"
    $migrator = "$project-migrator-$suffix"
    $swapped = $false
    $created = $false
    $complete = $false
    $encodedEmail = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($AdminEmail))
    $hasSessionVersion = (Invoke-Sql $database "SELECT count(*) FROM information_schema.columns WHERE table_schema='identity' AND table_name='users' AND column_name='session_version';") -eq '1'
    $columns = 'id,email,password_hash,status,created_at,updated_at,version'
    $versions = 'u.version+1'
    if ($hasSessionVersion) { $columns += ',session_version'; $versions = 'u.version,u.session_version+1' }
    $selectAdmin = @"
SELECT u.id,u.email,u.password_hash,u.status,u.created_at,u.updated_at,$versions
FROM identity.users u JOIN identity.user_roles ur ON ur.user_id=u.id JOIN identity.roles r ON r.id=ur.role_id
WHERE r.code='ADMIN' AND u.status='ACTIVE' AND lower(u.email)=lower(convert_from(decode('$encodedEmail','base64'),'UTF8'))
  AND (u.locked_until IS NULL OR u.locked_until <= now())
"@

    $previousServices = if ($Target -eq 'Support3200') { @('backend', 'frontend') }
                        else { @(Compose ps --status running --services | Where-Object { $_ -in @('frontend', 'backend') }) }
    try {
        Stop-Application
        $adminRow = Invoke-Sql $database "COPY ($selectAdmin) TO STDOUT WITH (FORMAT CSV);"
        if (@($adminRow).Count -ne 1 -or !$adminRow) { throw 'La cuenta indicada debe ser un único administrador activo y desbloqueado.' }
        Backup-Database
        Invoke-Sql postgres "CREATE DATABASE $candidate TEMPLATE template0;"
        $created = $true
        $migrationEnv = @('-e', "DB_URL=jdbc:postgresql://db:5432/$candidate", '-e', 'SEED_DEMO=false',
            '-e', 'ADMIN_EMAIL=', '-e', 'ADMIN_PASSWORD=', '-e', 'OUTBOX_WORKER_ENABLED=false', '-e', 'NOTIFICATION_RETENTION_ENABLED=false')
        if ($Target -eq 'Support3200') {
            $migrationVars = @('DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'JWT_ISSUER', 'SPRING_PROFILES_ACTIVE', 'SPRING_FLYWAY_TARGET')
            foreach ($name in $migrationVars) { $migrationEnv += @('-e', $name) }
            Invoke-Docker run --detach --name $migrator --network "${project}_default" --health-interval 3s `
                --health-retries 20 --health-cmd 'curl --fail --silent http://localhost:8080/actuator/health/readiness || exit 1' `
                @migrationEnv $containers.backend.Image | Out-Null
        } else { Compose run --detach --no-deps --name $migrator @migrationEnv backend | Out-Null }
        try {
            Wait-Healthy $migrator
        } finally {
            & $docker rm -f $migrator | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'No se pudo retirar el backend provisional.' }
        }

        $copyArgs = @('psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', $dbUser, '-d', $candidate,
            '-c', "COPY identity.users ($columns) FROM STDIN WITH (FORMAT CSV);")
        if ($Target -eq 'Support3200') {
            $adminRow | & $docker exec -i -e PGCLIENTENCODING=UTF8 $containers.db.Id @copyArgs 2>$null
        } else { $adminRow | & $docker compose @compose exec -T -e PGCLIENTENCODING=UTF8 db @copyArgs 2>$null }
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo conservar la cuenta administrativa.' }
        $adminRow = $null
        Invoke-Sql $candidate "INSERT INTO identity.user_roles(user_id,role_id) SELECT u.id,r.id FROM identity.users u CROSS JOIN identity.roles r WHERE r.code='ADMIN';"
        $snapshot = Invoke-Sql $candidate 'SELECT (SELECT count(*) FROM identity.users), (SELECT count(*) FROM identity.user_roles), (SELECT count(*) FROM customer.customers), (SELECT count(*) FROM catalog.products), (SELECT count(*) FROM orders.orders), (SELECT count(*) FROM sale.sales), (SELECT count(*) FROM payment.payments);'
        if ($snapshot -ne '1|1|0|0|0|0|0') { throw 'La base provisional no cumple el estado inicial esperado.' }

        # Rename both databases in a transaction. An open connection aborts the swap.
        Invoke-Sql postgres "BEGIN; ALTER DATABASE $database RENAME TO $previous; ALTER DATABASE $candidate RENAME TO $database; COMMIT;"
        $swapped = $true
        Start-Application
        Invoke-Capture 'reset' $evidence
        if ($Mode -eq 'Reset' -and $Target -eq 'Local') { Disable-LocalDemo }
        $complete = $true
        Invoke-Sql postgres "DROP DATABASE $previous;"
        Write-Host 'Base reiniciada. Quedó un administrador y el catálogo inicial de Flyway.'
    } finally {
        $adminRow = $null
        if (!$complete) {
            if ($swapped) {
                Stop-Application
                Invoke-Sql postgres "BEGIN; ALTER DATABASE $database RENAME TO $candidate; ALTER DATABASE $previous RENAME TO $database; COMMIT;"
                Write-Warning 'Se restauró la base original.'
            }
            if ($created) { Invoke-Sql postgres "DROP DATABASE IF EXISTS $candidate;" }
            if ($Mode -eq 'Reset' -and $previousServices.Count) {
                try {
                    if ($Target -eq 'Support3200') { Start-Application }
                    else { Compose start --wait @previousServices }
                }
                catch { Write-Warning 'La base original se conserva. Revisá el estado de los servicios antes de reintentar.' }
            }
        }
    }
}

if ($Mode -eq 'Reset' -and !$ConfirmReset) {
    throw 'Para reiniciar la base local, indicá la cuenta a conservar y agregá -ConfirmReset.'
}
if ($Target -eq 'Support3200' -and $Mode -eq 'Sample') { throw 'Sample crea su propio Docker descartable.' }
if ($ExampleData) {
    if ($Mode -ne 'Load' -or $Target -ne 'Support3200' -or $DataPath) {
        throw 'ExampleData requiere Load y Support3200, sin DataPath.'
    }
    $DataPath = Join-Path $PSScriptRoot 'support-onboarding.example.json'
}
if ($Mode -ne 'Sample' -and ((& git -C $root branch --show-current) -ne 'support')) {
    throw 'Reset y Load requieren el checkout local de la rama support.'
}
if ($Mode -eq 'Load' -and !$DataPath) { throw 'Load requiere -DataPath con el JSON validado para la carga.' }
if ($Mode -eq 'Reset' -and [string]::IsNullOrWhiteSpace($env:BACKUP_ENCRYPTION_KEY)) {
    $keyStore = if ($env:BACKUP_KEY_STORE_PATH) { $env:BACKUP_KEY_STORE_PATH }
                else { Join-Path $env:LOCALAPPDATA 'Distribuidora/postgres-backup-key.dpapi' }
    if (![IO.File]::Exists($keyStore)) {
        throw 'Inicializá la clave del respaldo con scripts/initialize-backup-key.ps1 antes de reiniciar.'
    }
}
if ($DataPath) { $DataPath = (Resolve-Path -LiteralPath $DataPath).Path }

Push-Location $root
try {
    if ($Target -eq 'Support3200') {
        $project = 'distribuidora-support-tests'
        $containers = @{}
        foreach ($service in @('db', 'backend', 'frontend')) {
            $container = (Invoke-Docker inspect "$project-$service-1" | ConvertFrom-Json)[0]
            if ($container.Config.Labels.'com.docker.compose.project' -ne $project -or
                $container.Config.Labels.'com.docker.compose.service' -ne $service -or
                $container.HostConfig.NetworkMode -ne "${project}_default") {
                throw "El contenedor $service no pertenece al entorno de pruebas esperado."
            }
            $containers[$service] = $container
        }
        $port = @($containers.frontend.HostConfig.PortBindings.'80/tcp')
        if ($port.Count -ne 1 -or $port[0].HostIp -ne '127.0.0.1' -or $port[0].HostPort -ne '3200' -or
            !($containers.db.Mounts | Where-Object { $_.Name -eq "${project}_support-data" -and $_.Destination -eq '/var/lib/postgresql/data' })) {
            throw 'El puerto o volumen no corresponde al Docker de pruebas 3200.'
        }
        $dbEnvironment = Get-ContainerEnvironment $containers.db
        $backendEnvironment = Get-ContainerEnvironment $containers.backend
        $database = $dbEnvironment.POSTGRES_DB
        $dbUser = $dbEnvironment.POSTGRES_USER
        if ($database -ne 'distribuidora' -or $dbUser -ne 'distribuidora' -or
            $backendEnvironment.DB_URL -ne "jdbc:postgresql://db:5432/$database" -or $backendEnvironment.DB_USERNAME -ne $dbUser -or
            $backendEnvironment.SEED_DEMO -ne 'false' -or $backendEnvironment.ADMIN_EMAIL -ne 'admin@demo') {
            throw 'La configuración de PostgreSQL o del administrador de pruebas no coincide.'
        }
        if ($PSBoundParameters.ContainsKey('AdminEmail') -and $AdminEmail -ne 'admin@demo') {
            throw 'La cuenta que se conserva en Support3200 debe ser admin@demo.'
        }
        $AdminEmail = 'admin@demo'
        if (!$AdminPassword -and $backendEnvironment.ADMIN_PASSWORD) {
            $AdminPassword = ConvertTo-SecureString $backendEnvironment.ADMIN_PASSWORD -AsPlainText -Force
        }
        foreach ($name in @('DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'JWT_ISSUER', 'SPRING_PROFILES_ACTIVE', 'SPRING_FLYWAY_TARGET')) {
            Set-RunEnvironment $name $backendEnvironment[$name]
        }
    }
    if ($Mode -eq 'Sample') {
        $project = "distribuidora-onboarding-$($runId.Substring($runId.Length - 8))"
        $AdminEmail = 'admin@ejemplo.test'
        $samplePassword = [guid]::NewGuid().ToString('N') + 'Aa!'
        $AdminPassword = ConvertTo-SecureString $samplePassword -AsPlainText -Force
        $DataPath = Join-Path $PSScriptRoot 'support-onboarding.example.json'
    } elseif (!$AdminPassword) {
        $AdminPassword = Read-Host "Contraseña actual de $AdminEmail" -AsSecureString
    }
    if (!$AdminPassword.Length) { throw 'La contraseña del administrador es obligatoria.' }

    if ($DataPath) {
        & $node $worker --validate $DataPath
        if ($LASTEXITCODE -ne 0) { throw 'El archivo de carga no pasó la validación.' }
    }
    & $node $worker --check-browser
    if ($LASTEXITCODE -ne 0) { throw 'Instalá las dependencias del frontend y Chromium de Playwright antes de continuar.' }
    [IO.Directory]::CreateDirectory($evidence) | Out-Null
    Set-RunEnvironment 'SEED_DEMO' 'false'
    $compose = @('-p', $project, '-f', $composeFile)
    if ($Mode -eq 'Sample') {
        $demoOverride = Join-Path $evidence 'sample-compose.yaml'
        @'
services:
  db:
    ports: !reset []
  backend:
    ports: !reset []
  frontend:
    ports: !override ["127.0.0.1::8080"]
'@ | Set-Content -LiteralPath $demoOverride -Encoding utf8
        $compose += @('-f', $demoOverride)
        Set-RunEnvironment 'ADMIN_EMAIL' $AdminEmail
        Set-RunEnvironment 'ADMIN_PASSWORD' $samplePassword
        Set-RunEnvironment 'POSTGRES_DB' 'distribuidora'
        Set-RunEnvironment 'POSTGRES_USER' 'distribuidora'
        Set-RunEnvironment 'POSTGRES_PASSWORD' ([guid]::NewGuid().ToString('N'))
        Set-RunEnvironment 'JWT_SECRET' ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
        Set-RunEnvironment 'SPRING_PROFILES_ACTIVE' 'local'
        Set-RunEnvironment 'EMAIL_WEBHOOK_URL' 'http://127.0.0.1:1'
        Set-RunEnvironment 'BACKUP_ENCRYPTION_KEY' ([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64)))
        $demoStarted = $true
    } else {
        $lockDirectory = Join-Path $root 'backups'
        [IO.Directory]::CreateDirectory($lockDirectory) | Out-Null
        $runLock = [IO.File]::Open((Join-Path $lockDirectory 'support-onboarding.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    }

    if ($Target -eq 'Support3200') {
        Invoke-Docker start $containers.db.Id | Out-Null
        Wait-Healthy $containers.db.Id
        Start-Application
        if ($Mode -eq 'Reset') {
            Invoke-Capture 'check-login' $evidence
            Reset-Database
        } else { Invoke-Capture 'load' $evidence }
        Write-Host "Capturas guardadas en $evidence"
        return
    }

    $config = (Compose config --format json | Out-String) | ConvertFrom-Json
    $database = $config.services.db.environment.POSTGRES_DB
    $dbUser = $config.services.db.environment.POSTGRES_USER
    if ($database -notmatch '^[a-z][a-z0-9_]{0,39}$' -or !$dbUser -or $database -in @('postgres', 'template0', 'template1')) {
        throw 'La configuración de la base no es válida para este flujo.'
    }
    if ($Mode -eq 'Reset') {
        $bootstrap = $config.services.backend.environment
        if ($bootstrap.ADMIN_PASSWORD -and $bootstrap.ADMIN_EMAIL -and $bootstrap.ADMIN_EMAIL -ne $AdminEmail) {
            throw 'ADMIN_EMAIL de Compose debe coincidir con el administrador que se va a conservar.'
        }
        $container = Compose ps -aq db
        if (!$container) { throw 'No se encontró el contenedor local de PostgreSQL de support.' }
        $db = (& $docker inspect $container | ConvertFrom-Json)[0]
        if ($db.Config.Labels.'com.docker.compose.project.config_files' -ne $composeFile -or
            !($db.Mounts | Where-Object { $_.Name -eq 'distribuidora_postgres-data' -and $_.Destination -eq '/var/lib/postgresql/data' }) -or
            "POSTGRES_DB=$database" -notin $db.Config.Env -or "POSTGRES_USER=$dbUser" -notin $db.Config.Env) {
            throw 'El contenedor y el volumen existentes no coinciden con el Compose local de support.'
        }
        Compose build backend frontend
        Compose start --wait db
    } else {
        Compose up -d --build --wait --wait-timeout 120
    }
    if ($Mode -eq 'Sample') { Invoke-Capture 'prepare' $evidence }
    if ($Mode -in @('Reset', 'Sample')) { Reset-Database }
    if ($Mode -in @('Load', 'Sample')) { Invoke-Capture 'load' $evidence }
    Write-Host "Capturas guardadas en $evidence"
} finally {
    try {
        if ($demoStarted) { Compose down --volumes }
    } finally {
        if ($demoOverride) { [IO.File]::Delete($demoOverride) }
        if ($runLock) { $runLock.Dispose() }
        foreach ($variable in $previousEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($variable.Key, $variable.Value, 'Process')
        }
        Pop-Location
    }
}
