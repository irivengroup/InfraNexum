[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('config','build','up','down','logs','smoke','backup','restore','rollback','reset','help')]
    [string]$Command = 'help',

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Services = @()
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$ComposeFile = Join-Path $ScriptDir 'compose.yaml'
$StateDir = Join-Path $RepoRoot '.infranexum-dev\state'
$BackupDir = Join-Path $StateDir 'backups'

function Get-ComposeBaseArguments {
    $base = @('compose')
    $envFile = Join-Path $ScriptDir '.env'
    if (Test-Path -LiteralPath $envFile) { $base += @('--env-file', $envFile) }
    $base += @('-f', $ComposeFile)
    return $base
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $base = @(Get-ComposeBaseArguments)
    & docker @base @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed with exit code $LASTEXITCODE" }
}

function Invoke-ComposeCapture {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $base = @(Get-ComposeBaseArguments)
    $output = & docker @base @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        $details = ($output | Out-String).Trim()
        throw "docker compose failed with exit code ${LASTEXITCODE}: $details"
    }
    return $output
}

function Get-PublishedPort {
    param(
        [Parameter(Mandatory = $true)][string]$Service,
        [Parameter(Mandatory = $true)][int]$ContainerPort
    )
    $binding = ((Invoke-ComposeCapture port $Service $ContainerPort) | Out-String).Trim()
    if (-not $binding) { throw "Compose does not publish $Service container port $ContainerPort to the host" }
    if ($binding -notmatch ':(?<port>[0-9]+)$') { throw "Unexpected Compose port binding for $Service/$ContainerPort: $binding" }
    return [int]$Matches['port']
}

function Assert-ComposeServiceRunning {
    param([Parameter(Mandatory = $true)][string]$Service)
    $running = @((Invoke-ComposeCapture ps --status running --services) | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ })
    if ($running -notcontains $Service) {
        Write-Warning "Compose service '$Service' is not running; current topology and recent logs follow."
        try { Invoke-Compose ps } catch { Write-Warning $_ }
        try { Invoke-Compose logs --no-color --tail=200 $Service } catch { Write-Warning $_ }
        throw "Compose service '$Service' is not running"
    }
}


function Assert-Repository {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is required' }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 plugin is required' }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot 'VERSION'))) { throw "InfraNexum repository root not found: $RepoRoot" }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot 'src\distribution\migrations'))) { throw "Migration catalogue not found below $RepoRoot" }
}

function New-DatabaseBackup {
    Assert-Repository
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $backup = Join-Path $BackupDir "infranexum-$stamp.dump"
    $remote = '/tmp/infranexum-dev-backup.dump'
    Invoke-Compose exec -T postgres sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; pg_dump --format=custom --no-owner --no-privileges --username=infranexum --dbname=infranexum --file=/tmp/infranexum-dev-backup.dump'
    Invoke-Compose cp "postgres:$remote" $backup
    try { Invoke-Compose exec -T postgres rm -f $remote } catch { Write-Warning $_ }
    if (-not (Test-Path -LiteralPath $backup) -or (Get-Item -LiteralPath $backup).Length -eq 0) { throw "Backup is empty: $backup" }
    return $backup
}

function Invoke-Smoke {
    Assert-Repository
    Assert-ComposeServiceRunning -Service 'postgres'
    Assert-ComposeServiceRunning -Service 'server'
    $postgresPort = Get-PublishedPort -Service 'postgres' -ContainerPort 5432
    $port = Get-PublishedPort -Service 'server' -ContainerPort 8080
    Write-Output "Compose bindings: postgres=127.0.0.1:$postgresPort server=127.0.0.1:$port"
    $readiness = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$port/actuator/health/readiness" -TimeoutSec 10
    if ($readiness.status -ne 'UP') { throw 'Server readiness is not UP' }
    $workersMetric = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$port/actuator/metrics/infranexum.workers.ready" -TimeoutSec 10
    if ($workersMetric.name -ne 'infranexum.workers.ready') { throw 'Workers readiness metric is unavailable' }
    $correlationId = '018bcfe5-6800-7001-8000-000000000001'
    $buildResponse = Invoke-WebRequest -Method Get -Uri "http://127.0.0.1:$port/api/v1/system/build" `
        -Headers @{ 'X-Correlation-ID' = $correlationId } -TimeoutSec 10
    $build = $buildResponse.Content | ConvertFrom-Json
    if ($build.product -ne 'InfraNexum') { throw 'Unexpected build endpoint product' }
    if ($buildResponse.Headers['X-Correlation-ID'] -ne $correlationId) { throw 'Correlation header was not propagated' }

    $invalidBody = [System.IO.Path]::GetTempFileName()
    $invalidHeaders = [System.IO.Path]::GetTempFileName()
    try {
        $status = & curl.exe --silent --show-error --output $invalidBody --dump-header $invalidHeaders `
            --write-out '%{http_code}' --header 'X-Correlation-ID: invalid-secret-value' `
            "http://127.0.0.1:$port/api/v1/system/build"
        if ($LASTEXITCODE -ne 0) { throw "curl.exe correlation rejection probe failed with exit code $LASTEXITCODE" }
        if ($status -ne '400') { throw "Invalid correlation identifier returned HTTP $status instead of 400" }
        $problem = Get-Content -LiteralPath $invalidBody -Raw
        if ($problem -notmatch 'INFRANEXUM_INVALID_CORRELATION_ID') { throw 'Invalid correlation problem code is missing' }
        if ($problem -match 'invalid-secret-value') { throw 'Rejected correlation value was reflected in the response' }
        $headerText = Get-Content -LiteralPath $invalidHeaders -Raw
        if ($headerText -notmatch '(?im)^X-Correlation-ID:\s*[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\s*$') {
            throw 'Server-generated UUIDv7 correlation header is missing from the rejection response'
        }
    } finally {
        Remove-Item -LiteralPath $invalidBody, $invalidHeaders -Force -ErrorAction SilentlyContinue
    }
    Write-Output 'compose-smoke: PASS'
}

switch ($Command) {
    'config' { Assert-Repository; Invoke-Compose config --quiet }
    'build' { Assert-Repository; Invoke-Compose config --quiet; Invoke-Compose build --pull }
    'up' { Assert-Repository; Invoke-Compose config --quiet; Invoke-Compose up --detach --build --wait server }
    'down' { Assert-Repository; Invoke-Compose down --remove-orphans }
    'logs' {
        Assert-Repository
        if ($Services.Count -gt 0) {
            Invoke-Compose logs --no-color --tail=200 @Services
        } else {
            Invoke-Compose logs --no-color --tail=200 server postgres migrate
        }
    }
    'smoke' { Invoke-Smoke }
    'backup' { New-DatabaseBackup }
    'restore' {
        Assert-Repository
        if ($env:CONFIRM_INFRANEXUM_RESTORE -ne 'YES') { throw 'Refusing restore; set CONFIRM_INFRANEXUM_RESTORE=YES' }
        if (-not $env:BACKUP_FILE) { throw 'BACKUP_FILE is required' }
        $backup = (Resolve-Path -LiteralPath $env:BACKUP_FILE).Path
        if ((Get-Item -LiteralPath $backup).Length -eq 0) { throw "Backup is empty: $backup" }
        $remote = '/tmp/infranexum-dev-restore.dump'
        Invoke-Compose up --detach --wait postgres
        try { Invoke-Compose stop server } catch { Write-Warning $_ }
        Invoke-Compose cp $backup "postgres:$remote"
        try {
            Invoke-Compose exec -T postgres sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; dropdb --if-exists --username=infranexum infranexum; createdb --username=infranexum --owner=infranexum infranexum; pg_restore --exit-on-error --no-owner --no-privileges --username=infranexum --dbname=infranexum /tmp/infranexum-dev-restore.dump'
        } finally {
            try { Invoke-Compose exec -T postgres rm -f $remote } catch { Write-Warning $_ }
        }
        Invoke-Compose run --rm migrate
        Invoke-Compose up --detach --wait server
    }
    'rollback' {
        Assert-Repository
        if (-not $env:MIGRATION_ID) { throw 'MIGRATION_ID is required' }
        if ($env:CONFIRM_INFRANEXUM_ROLLBACK -ne 'YES') { throw 'Refusing rollback; set CONFIRM_INFRANEXUM_ROLLBACK=YES' }
        Invoke-Compose up --detach --wait postgres
        $backup = New-DatabaseBackup
        Write-Output "Pre-rollback backup: $backup"
        try { Invoke-Compose stop server } catch { Write-Warning $_ }
        Invoke-Compose --profile maintenance run --rm -e "MIGRATION_ID=$($env:MIGRATION_ID)" -e CONFIRM_INFRANEXUM_ROLLBACK=YES rollback
        Write-Output "Rollback completed. Server remains stopped; restart only with a build compatible with migration $($env:MIGRATION_ID)."
    }
    'reset' {
        Assert-Repository
        if ($env:CONFIRM_INFRANEXUM_VOLUME_DELETE -ne 'YES') { throw 'Refusing volume deletion; set CONFIRM_INFRANEXUM_VOLUME_DELETE=YES' }
        Invoke-Compose down --volumes --remove-orphans
        Write-Output 'InfraNexum developer Compose volumes removed'
    }
    'help' {
        @'
Usage: .\docker\dev-compose.ps1 COMMAND [SERVICE ...]
Commands: config build up down logs smoke backup restore rollback reset

Start the complete developer topology:
  .\docker\dev-compose.ps1 up

Equivalent direct Compose command:
  docker compose up --detach --build --wait server

Migration diagnostics:
  .\docker\dev-compose.ps1 logs migrate
  docker compose logs migrate

Destructive/restore operations require:
  $env:BACKUP_FILE='...'; $env:CONFIRM_INFRANEXUM_RESTORE='YES'; .\docker\dev-compose.ps1 restore
  $env:MIGRATION_ID='0006'; $env:CONFIRM_INFRANEXUM_ROLLBACK='YES'; .\docker\dev-compose.ps1 rollback
  $env:CONFIRM_INFRANEXUM_VOLUME_DELETE='YES'; .\docker\dev-compose.ps1 reset
'@
    }
}
