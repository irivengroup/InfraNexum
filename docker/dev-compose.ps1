[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('config','build','up','down','logs','smoke','ha-smoke','backup','restore','rollback','reset','help')]
    [string]$Command = 'help',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Services = @()
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$ComposeFile = Join-Path $ScriptDir 'compose.yaml'
$BackupDir = Join-Path $RepoRoot '.infranexum-dev\state\backups'
$ClusterServices = @('etcd-1','etcd-2','etcd-3','postgres-1','postgres-2','postgres-3','postgres','server-1','server-2','server-3','server-4','server')

function Get-ComposeBaseArguments {
    $base = @('compose')
    $envFile = Join-Path $ScriptDir '.env'
    if (Test-Path -LiteralPath $envFile) { $base += @('--env-file', $envFile) }
    $base += @('-f', $ComposeFile)
    return $base
}
function Invoke-Compose {
    # Native Docker/Compose switches must remain opaque to PowerShell parameter binding.
    # Using the automatic $args array prevents tokens such as -e from being resolved
    # as abbreviated PowerShell common parameters (for example -ErrorAction).
    $base = @(Get-ComposeBaseArguments); & docker @base @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed with exit code $LASTEXITCODE" }
}
function Invoke-ComposeCapture {
    # Keep native short options in the automatic argument vector for the same reason.
    $base = @(Get-ComposeBaseArguments); $output = & docker @base @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed with exit code ${LASTEXITCODE}: $(($output | Out-String).Trim())" }
    return $output
}
function Assert-Repository {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is required' }
    & docker compose version *> $null; if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 plugin is required' }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot 'VERSION'))) { throw "InfraNexum repository root not found: $RepoRoot" }
}
function Get-PublishedPort {
    param([Parameter(Mandatory=$true)][string]$Service,[Parameter(Mandatory=$true)][int]$ContainerPort)
    $binding = $null
    try { $binding = ((Invoke-ComposeCapture port $Service $ContainerPort) | Out-String).Trim() } catch { Write-Warning "docker compose port failed for ${Service}/${ContainerPort}; falling back to docker inspect" }
    if (-not $binding) {
        $cid = ((Invoke-ComposeCapture ps -q $Service) | Out-String).Trim(); if (-not $cid) { throw "No container for $Service" }
        $key = "${ContainerPort}/tcp"; $json = (& docker inspect --format '{{json .NetworkSettings.Ports}}' $cid 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw "docker inspect failed for $Service" }
        $ports = $json | ConvertFrom-Json; $prop = $ports.PSObject.Properties[$key]
        if ($null -eq $prop -or $null -eq $prop.Value) { throw "Compose does not publish ${Service}/${ContainerPort}" }
        $host = @($prop.Value)[0]; $binding = "$($host.HostIp):$($host.HostPort)"
    }
    if ($binding -notmatch '^127\.0\.0\.1:(?<port>[0-9]+)$') { throw "Unexpected Compose port binding for ${Service}/${ContainerPort}: $binding" }
    return [int]$Matches['port']
}
function Assert-ComposeServiceHealthy {
    param([Parameter(Mandatory=$true)][string]$Service)
    $running = @((Invoke-ComposeCapture ps --status running --services) | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ })
    if ($running -notcontains $Service) { try { Invoke-Compose ps } catch {}; try { Invoke-Compose logs --no-color --tail=200 $Service } catch {}; throw "Service $Service is not running" }
    $cid = ((Invoke-ComposeCapture ps -q $Service) | Out-String).Trim()
    $health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $cid 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $health -ne 'healthy') { try { Invoke-Compose logs --no-color --tail=200 $Service } catch {}; throw "Service $Service health=$health" }
}
function Invoke-DatabaseScalar {
    param([Parameter(Mandatory=$true)][string]$Sql)
    $shell = 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; exec psql --no-psqlrc --tuples-only --no-align --host=postgres --port=5432 --username=infranexum --dbname=infranexum --command "$INFRANEXUM_SQL"'
    return ((Invoke-ComposeCapture run --rm --no-deps -e "INFRANEXUM_SQL=$Sql" --entrypoint /bin/sh migrate -eu -c $shell) | Out-String).Trim()
}
function New-DatabaseBackup {
    Assert-Repository; New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $backup = Join-Path $BackupDir "infranexum-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')).dump"; $remote='/tmp/infranexum-dev-backup.dump'
    Invoke-Compose --profile maintenance up --detach db-admin
    try {
        Invoke-Compose exec -T db-admin sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; pg_dump --format=custom --no-owner --no-privileges --host=postgres --port=5432 --username=infranexum --dbname=infranexum --file=/tmp/infranexum-dev-backup.dump'
        Invoke-Compose cp "db-admin:$remote" $backup; Invoke-Compose exec -T db-admin rm -f $remote
    } finally { try { Invoke-Compose stop db-admin } catch {} }
    if (-not (Test-Path $backup) -or (Get-Item $backup).Length -eq 0) { throw "Backup is empty: $backup" }; return $backup
}
function Invoke-Smoke {
    Assert-Repository; foreach ($service in $ClusterServices) { Assert-ComposeServiceHealthy $service }
    $writer=Get-PublishedPort postgres 5432; $reader=Get-PublishedPort postgres 5433; $port=Get-PublishedPort server 8080
    Write-Output "Compose PRO bindings: writer=127.0.0.1:$writer replicas=127.0.0.1:$reader server=127.0.0.1:$port"
    $streaming=[int](Invoke-DatabaseScalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'"); if ($streaming -lt 2) { throw "Expected two streaming standbys; observed $streaming" }
    $sync=[int](Invoke-DatabaseScalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state IN ('sync','quorum')"); if ($sync -lt 1) { throw 'No synchronous PostgreSQL standby' }
    $ready=Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health/readiness" -TimeoutSec 10; if ($ready.status -ne 'UP') { throw 'Server router readiness is not UP' }
    $metric=Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/metrics/infranexum.workers.ready" -TimeoutSec 10; if ($metric.name -ne 'infranexum.workers.ready') { throw 'Workers metric unavailable' }
    $cid='018bcfe5-6800-7001-8000-000000000001'; $response=Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/v1/system/build" -Headers @{'X-Correlation-ID'=$cid} -TimeoutSec 10
    $build=$response.Content | ConvertFrom-Json; if ($build.instanceId -notmatch '^server-pro-[1-4]$') { throw "Unexpected routed instance $($build.instanceId)" }; if ($response.Headers['X-Correlation-ID'] -ne $cid) { throw 'Correlation was not propagated' }
    Write-Output "compose-smoke: PASS (streaming=$streaming synchronous=$sync Server=4)"
}
function Get-PatroniPrimaryService {
    foreach ($service in @('postgres-1','postgres-2','postgres-3')) {
        try { $code=((Invoke-ComposeCapture exec -T $service sh -c 'curl --silent --output /dev/null --write-out "%{http_code}" http://127.0.0.1:8008/primary') | Out-String).Trim(); if ($code -eq '200') { return $service } } catch {}
    }; throw 'Unable to identify Patroni primary'
}
function Invoke-HaSmoke {
    Assert-Repository; Invoke-Smoke; $primary=Get-PatroniPrimaryService; Write-Output "Stopping current Patroni primary: $primary"; Invoke-Compose stop $primary
    try {
        $deadline=[DateTime]::UtcNow.AddSeconds(60); $replacement=$null
        do { Start-Sleep 2; try { $replacement=Get-PatroniPrimaryService } catch { $replacement=$null } } while (-not $replacement -and [DateTime]::UtcNow -lt $deadline)
        if (-not $replacement -or $replacement -eq $primary) { throw 'No replacement primary within 60 seconds' }
        if ((Invoke-DatabaseScalar 'SELECT 1') -ne '1') { throw 'Writer endpoint did not recover' }
        $port=Get-PublishedPort server 8080; if ((Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health/readiness" -TimeoutSec 10).status -ne 'UP') { throw 'Server readiness lost during failover' }
    } finally { Invoke-Compose start $primary }
    $deadline=[DateTime]::UtcNow.AddSeconds(90); $healthy=$false
    do { Start-Sleep 3; try { Assert-ComposeServiceHealthy $primary; $healthy=$true } catch { $healthy=$false } } while (-not $healthy -and [DateTime]::UtcNow -lt $deadline)
    if (-not $healthy) { throw "Former primary $primary did not rejoin within 90 seconds" }
    $deadline=[DateTime]::UtcNow.AddSeconds(90); $streaming=0
    do { Start-Sleep 3; try { $streaming=[int](Invoke-DatabaseScalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'") } catch { $streaming=0 } } while ($streaming -lt 2 -and [DateTime]::UtcNow -lt $deadline)
    if ($streaming -lt 2) { throw "Cluster did not return to two streaming standbys; observed $streaming" }; Write-Output 'compose-ha-smoke: PASS'
}

switch ($Command) {
 'config' { Assert-Repository; Invoke-Compose config --quiet }
 'build' { Assert-Repository; Invoke-Compose config --quiet; Invoke-Compose build --pull }
 'up' { Assert-Repository; Invoke-Compose config --quiet; Invoke-Compose up --detach --build --wait server }
 'down' { Assert-Repository; Invoke-Compose down --remove-orphans }
 'logs' { Assert-Repository; if ($Services.Count) { Invoke-Compose logs --no-color --tail=200 @Services } else { Invoke-Compose logs --no-color --tail=200 server server-1 server-2 server-3 server-4 postgres postgres-1 postgres-2 postgres-3 migrate } }
 'smoke' { Invoke-Smoke }
 'ha-smoke' { Invoke-HaSmoke }
 'backup' { New-DatabaseBackup }
 'restore' { Assert-Repository; if ($env:CONFIRM_INFRANEXUM_RESTORE -ne 'YES') { throw 'Refusing restore; set CONFIRM_INFRANEXUM_RESTORE=YES' }; if (-not $env:BACKUP_FILE) { throw 'BACKUP_FILE is required' }; $backup=(Resolve-Path $env:BACKUP_FILE).Path; Invoke-Compose up --detach --wait postgres; Invoke-Compose --profile maintenance up --detach db-admin; try { Invoke-Compose stop server server-1 server-2 server-3 server-4 } catch {}; $remote='/tmp/infranexum-dev-restore.dump'; Invoke-Compose cp $backup "db-admin:$remote"; Invoke-Compose exec -T db-admin sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; dropdb --if-exists --host=postgres --port=5432 --username=infranexum --maintenance-db=postgres infranexum; createdb --host=postgres --port=5432 --username=infranexum --maintenance-db=postgres --owner=infranexum infranexum; pg_restore --exit-on-error --no-owner --no-privileges --host=postgres --port=5432 --username=infranexum --dbname=infranexum /tmp/infranexum-dev-restore.dump'; Invoke-Compose exec -T db-admin rm -f $remote; Invoke-Compose stop db-admin; Invoke-Compose run --rm migrate; Invoke-Compose up --detach --wait server }
 'rollback' { Assert-Repository; if (-not $env:MIGRATION_ID -or $env:CONFIRM_INFRANEXUM_ROLLBACK -ne 'YES') { throw 'MIGRATION_ID and CONFIRM_INFRANEXUM_ROLLBACK=YES are required' }; Invoke-Compose up --detach --wait postgres; $backup=New-DatabaseBackup; Write-Output "Pre-rollback backup: $backup"; try { Invoke-Compose stop server server-1 server-2 server-3 server-4 } catch {}; Invoke-Compose --profile maintenance run --rm -e "MIGRATION_ID=$($env:MIGRATION_ID)" -e CONFIRM_INFRANEXUM_ROLLBACK=YES rollback }
 'reset' { Assert-Repository; if ($env:CONFIRM_INFRANEXUM_VOLUME_DELETE -ne 'YES') { throw 'Refusing volume deletion' }; Invoke-Compose down --volumes --remove-orphans }
 'help' { Write-Output 'Commands: config build up down logs smoke ha-smoke backup restore rollback reset' }
}
