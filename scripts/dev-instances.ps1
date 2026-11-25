param(
    [ValidateSet('update', 'start', 'tunnel', 'stop', 'status', 'logs', 'prune')]
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

if ($Action -eq 'prune') {
    $releasesRoot = Join-Path $InstanceStateRoot 'releases'
    if (-not (Test-Path -LiteralPath $releasesRoot -PathType Container)) { return }
    $resolvedRoot = (Resolve-Path -LiteralPath $releasesRoot).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
    $expectedRoot = [IO.Path]::GetFullPath($releasesRoot)
    if ($resolvedRoot -ne $expectedRoot -or
        ((Get-Item -LiteralPath $resolvedRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        ((Get-Item -LiteralPath $InstanceStateRoot).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'The release directory must be inside the local instance directory.'
    }
    $selectedRevision = if (Test-Path -LiteralPath $InstanceStateFile) {
        (Get-Content -LiteralPath $InstanceStateFile -Raw | ConvertFrom-Json).revision
    }
    $releases = @(Get-ChildItem -LiteralPath $resolvedRoot -Directory |
        Where-Object { $_.Name -cmatch '^[0-9a-f]{40}$' -and -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
        Sort-Object LastWriteTime -Descending)
    $retained = @($selectedRevision) + @($releases.Name | Where-Object { $_ -ne $selectedRevision })
    $retained = @($retained | Where-Object { $_ -in $releases.Name } | Select-Object -First 2)
    foreach ($release in $releases) {
        if ($release.Name -in $retained) { continue }
        $path = (Resolve-Path -LiteralPath $release.FullName).Path
        if ((Split-Path -Parent $path) -ne $resolvedRoot) { throw "Unexpected release path: $path" }
        Remove-Item -LiteralPath $path -Recurse -Force
        Write-Host "Removed cached release $($release.Name)"
    }
    return
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
        try {
            & git -C $InstanceRoot archive --format=tar "--output=$InstanceArchive" $InstanceRevision
            if ($LASTEXITCODE -ne 0) { throw 'Could not export the main commit.' }
            & tar -xf $InstanceArchive -C $InstanceReleaseRoot
            if ($LASTEXITCODE -ne 0) { throw 'Could not extract the main commit.' }
        } finally { [IO.File]::Delete($InstanceArchive) }
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
            $InstanceExistingLabels = & docker image inspect $InstanceImageTag --format '{{json .Config.Labels}}' | ConvertFrom-Json
            if ($LASTEXITCODE -ne 0 -or $InstanceExistingLabels.'org.opencontainers.image.revision' -ne $InstanceRevision) { throw "Image $InstanceImageTag does not match its main revision." }
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
$InstancePreviousTunnelMode = $env:DEV_TUNNEL_ENABLED
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
            $env:DEV_TUNNEL_ENABLED = 'true'
            $environment = & docker @InstanceTunnelArgs config --environment
            if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the tunnel configuration.' }
            $settings = @{}
            foreach ($line in $environment) {
                $entry = $line -split '=', 2
                if ($entry.Count -eq 2) { $settings[$entry[0]] = $entry[1] }
            }
            foreach ($key in @('NGROK_AUTHTOKEN', 'DEV_NGROK_URL', 'JWT_SECRET', 'DEV_JWT_SECRET_B', 'DEV_ADMIN_PASSWORD')) {
                if ([string]::IsNullOrWhiteSpace($settings[$key])) {
                    throw "Set $key in .env.development-tunnel.local or in the environment before starting the tunnel."
                }
            }
            if ($settings.JWT_SECRET.Length -lt 32 -or $settings.DEV_JWT_SECRET_B.Length -lt 32 -or
                $settings.JWT_SECRET -ceq $settings.DEV_JWT_SECRET_B -or
                $settings.JWT_SECRET.StartsWith('development-system-') -or
                $settings.DEV_JWT_SECRET_B.StartsWith('development-system-') -or
                $settings.DEV_ADMIN_PASSWORD.Length -lt 12 -or $settings.DEV_ADMIN_PASSWORD -ceq 'ChangeMe123!') {
                throw 'Use two distinct JWT secrets of at least 32 characters and a custom administrator password of at least 12 characters.'
            }
            $resolved = & docker @InstanceTunnelArgs config --format json | ConvertFrom-Json
            if ($LASTEXITCODE -ne 0 -or $resolved.services.'backend-a'.environment.JWT_SECRET -cne $settings.JWT_SECRET -or
                $resolved.services.'backend-b'.environment.JWT_SECRET -cne $settings.DEV_JWT_SECRET_B) {
                throw 'The selected release cannot apply the tunnel secrets. Select a release with the updated Compose configuration.'
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
    $env:DEV_TUNNEL_ENABLED = $InstancePreviousTunnelMode
}
