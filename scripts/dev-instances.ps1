param(
    [ValidateSet('update', 'start', 'tunnel', 'stop', 'status', 'logs')]
    [string]$Action = 'start'
)

$ErrorActionPreference = 'Stop'
$InstanceRoot = Split-Path -Parent $PSScriptRoot
$InstanceStateRoot = Join-Path $InstanceRoot '.main-instances'
$InstanceStateFile = Join-Path $InstanceStateRoot 'current.json'
$InstanceTunnelEnvironment = Join-Path $InstanceRoot '.env.development-tunnel.local'

function Invoke-InstanceDocker {
    param([string[]]$DockerArguments)
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed (exit $LASTEXITCODE). Check Docker Desktop and the container logs." }
}

if ($Action -eq 'update') {
    & git -C $InstanceRoot fetch origin main
    if ($LASTEXITCODE -ne 0) { throw 'Could not fetch origin/main. Existing images have not been changed.' }
    $InstanceRevision = (& git -C $InstanceRoot rev-parse --verify 'refs/remotes/origin/main^{commit}').Trim()
    if ($LASTEXITCODE -ne 0 -or $InstanceRevision -notmatch '^[0-9a-f]{40}$') { throw 'Could not resolve origin/main.' }
    $InstanceReleaseRoot = Join-Path $InstanceStateRoot "releases/$InstanceRevision"
    $InstanceCompose = Join-Path $InstanceReleaseRoot 'compose.development.yaml'
    if (-not (Test-Path -LiteralPath $InstanceCompose)) {
        New-Item -ItemType Directory -Path $InstanceReleaseRoot -Force | Out-Null
        $InstanceArchive = Join-Path $InstanceStateRoot "$InstanceRevision.tar"
        & git -C $InstanceRoot archive --format=tar "--output=$InstanceArchive" $InstanceRevision
        if ($LASTEXITCODE -ne 0) { throw 'Could not export the main commit.' }
        & tar -xf $InstanceArchive -C $InstanceReleaseRoot
        if ($LASTEXITCODE -ne 0) { throw 'Could not extract the main commit.' }
        if (-not (Test-Path -LiteralPath $InstanceCompose)) { throw 'origin/main does not contain the instance infrastructure. Merge it first.' }
    }

    $InstanceImages = @(
        @{ Name = 'backend'; Context = 'backend'; Arguments = @() },
        @{ Name = 'frontend-a'; Context = 'frontend'; Arguments = @('--target', 'instance', '--build-arg', 'APP_BASE_PATH=/a/') },
        @{ Name = 'frontend-b'; Context = 'frontend'; Arguments = @('--target', 'instance', '--build-arg', 'APP_BASE_PATH=/b/') },
        @{ Name = 'gateway'; Context = '.'; Arguments = @('-f', (Join-Path $InstanceReleaseRoot 'tools/development-gateway/Dockerfile')) }
    )
    foreach ($InstanceImage in $InstanceImages) {
        $InstanceImageTag = "distribuidora-main-$($InstanceImage.Name):$InstanceRevision"
        $InstanceExistingImage = & docker image ls --quiet --filter "reference=$InstanceImageTag"
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the local Docker images.' }
        if ($InstanceExistingImage) {
            $InstanceExistingRevision = & docker image inspect $InstanceImageTag --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'
            if ($LASTEXITCODE -ne 0 -or $InstanceExistingRevision -ne $InstanceRevision) { throw "Image $InstanceImageTag does not match its main revision." }
            Write-Host "Keeping existing main image $InstanceImageTag"
            continue
        }
        $InstanceBuildArgs = @('build', '--label', "org.opencontainers.image.revision=$InstanceRevision", '--label', 'org.opencontainers.image.source=https://github.com/Martinrc93/distribuidoraCore', '-t', $InstanceImageTag)
        $InstanceBuildArgs += $InstanceImage.Arguments
        $InstanceBuildArgs += (Join-Path $InstanceReleaseRoot $InstanceImage.Context)
        Invoke-InstanceDocker -DockerArguments $InstanceBuildArgs
    }
} else {
    if (-not (Test-Path -LiteralPath $InstanceStateFile)) { throw 'No main images are selected. Run .\dev-instances.cmd update first.' }
    $InstanceState = Get-Content -LiteralPath $InstanceStateFile -Raw | ConvertFrom-Json
    $InstanceRevision = $InstanceState.revision
    if ($InstanceRevision -notmatch '^[0-9a-f]{40}$') { throw 'Invalid main image revision in current.json.' }
    $InstanceReleaseRoot = Join-Path $InstanceStateRoot "releases/$InstanceRevision"
    $InstanceCompose = Join-Path $InstanceReleaseRoot 'compose.development.yaml'
    if (-not (Test-Path -LiteralPath $InstanceCompose)) { throw 'The saved main release is missing. Run .\dev-instances.cmd update.' }
}

$InstancePreviousTag = $env:DISTRIBUIDORA_MAIN_IMAGE_TAG
try {
    $env:DISTRIBUIDORA_MAIN_IMAGE_TAG = $InstanceRevision
    $InstanceArgs = @('compose', '-p', 'distribuidora-dev-instances', '-f', $InstanceCompose)
    if (Test-Path -LiteralPath $InstanceTunnelEnvironment) {
        $InstanceArgs += @('--env-file', $InstanceTunnelEnvironment)
    }
    $InstanceTunnelArgs = $InstanceArgs + @('--profile', 'tunnel')

    switch ($Action) {
        { $_ -in 'update', 'start' } {
            Invoke-InstanceDocker -DockerArguments ($InstanceArgs + @('up', '-d', '--no-build', '--wait', '--wait-timeout', '300'))
            if ($Action -eq 'update') {
                @{ revision = $InstanceRevision } | ConvertTo-Json | Set-Content -LiteralPath $InstanceStateFile -Encoding UTF8
            }
            $InstancePort = if ($env:DEV_GATEWAY_PORT) { $env:DEV_GATEWAY_PORT } else { '3100' }
            Write-Host "Main commit $InstanceRevision is running at http://localhost:$InstancePort/"
        }
        'tunnel' {
            if (-not (Test-Path -LiteralPath $InstanceTunnelEnvironment) -and
                (-not $env:NGROK_AUTHTOKEN -or -not $env:DEV_NGROK_URL)) {
                throw 'Set NGROK_AUTHTOKEN and DEV_NGROK_URL, or create .env.development-tunnel.local, before starting the tunnel.'
            }
            Invoke-InstanceDocker -DockerArguments ($InstanceTunnelArgs + @('up', '-d', '--no-build', 'ngrok'))
            Write-Host 'The tunnel points to the system selector. Use the configured DEV_NGROK_URL.'
        }
        'stop' { Invoke-InstanceDocker -DockerArguments ($InstanceTunnelArgs + @('down')) }
        'status' {
            Write-Host "Pinned main commit: $InstanceRevision"
            Invoke-InstanceDocker -DockerArguments ($InstanceTunnelArgs + @('ps', '-a'))
        }
        'logs' { Invoke-InstanceDocker -DockerArguments ($InstanceTunnelArgs + @('logs', '--tail', '100', '-f')) }
    }
} finally {
    $env:DISTRIBUIDORA_MAIN_IMAGE_TAG = $InstancePreviousTag
}
