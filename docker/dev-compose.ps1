[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('config','build','up','down','logs','smoke','ha-smoke','credentials','backup','restore','rollback','reset','help')]
    [string]$Command = 'help',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Services = @()
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$ComposeFile = Join-Path $ScriptDir 'compose.yaml'
$BackupDir = Join-Path $RepoRoot '.infranexum-dev\state\backups'
$ClusterServices = @('etcd-1','etcd-2','etcd-3','postgres-1','postgres-2','postgres-3','postgres','server-1','server-2','server-3','server-4','server','web-1','web-2','web')

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
    # Capture stdout and stderr independently. Docker Compose writes lifecycle
    # progress (for example, ephemeral container Creating/Created messages) to
    # stderr even when the command succeeds. Mixing the streams corrupts scalar
    # stdout consumers such as Invoke-DatabaseScalar. ProcessStartInfo also keeps
    # native short switches opaque to PowerShell parameter binding.
    $base = @(Get-ComposeBaseArguments)
    $nativeArguments = @($base) + @($args)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'docker'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $nativeArguments) {
        [void]$startInfo.ArgumentList.Add([string]$argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw 'Unable to start Docker CLI' }
        # Read both redirected streams asynchronously to avoid pipe deadlocks.
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()

        if ($process.ExitCode -ne 0) {
            $details = @($stderr.Trim(), $stdout.Trim()) | Where-Object { $_ }
            throw "docker compose failed with exit code $($process.ExitCode): $($details -join [Environment]::NewLine)"
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            Write-Verbose $stderr.Trim()
        }
        # Preserve native-command line semantics for callers that enumerate output.
        return @($stdout -split "`r?`n" | Where-Object { $_ -ne '' })
    } finally {
        $process.Dispose()
    }
}
function Assert-Repository {
    if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required for InfraNexum developer tooling' }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is required' }
    & docker compose version *> $null; if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 plugin is required' }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot 'VERSION'))) { throw "InfraNexum repository root not found: $RepoRoot" }
}
function Convert-WebResponseContentToText {
    param([AllowNull()][object]$Content)

    if ($null -eq $Content) { return '' }
    if ($Content -is [string]) { return $Content }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    if ($Content -is [System.Net.Http.HttpContent]) {
        return $Content.ReadAsStringAsync().GetAwaiter().GetResult()
    }
    if ($Content -is [System.IO.Stream]) {
        $buffer = [System.IO.MemoryStream]::new()
        try {
            $Content.CopyTo($buffer)
            return [System.Text.Encoding]::UTF8.GetString($buffer.ToArray())
        } finally {
            $buffer.Dispose()
        }
    }

    # Some PowerShell/native HTTP paths surface byte content as an enumerable
    # rather than a strongly typed byte[]. Detect that shape explicitly instead
    # of relying on [string] casting, which renders decimal byte values such as
    # "123 34 115 ..." and makes valid JSON impossible to parse.
    if ($Content -is [System.Collections.IEnumerable]) {
        $items = @($Content)
        if ($items.Count -eq 0) { return '' }
        $allBytes = $true
        foreach ($item in $items) {
            if ($item -isnot [byte]) { $allBytes = $false; break }
        }
        if ($allBytes) {
            return [System.Text.Encoding]::UTF8.GetString([byte[]]$items)
        }
    }

    return [string]$Content
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
function Test-ComposeServiceHealthy {
    param([Parameter(Mandatory=$true)][string]$Service)
    try {
        $running = @((Invoke-ComposeCapture ps --status running --services) | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ })
        if ($running -notcontains $Service) { return $false }
        $cid = ((Invoke-ComposeCapture ps -q $Service) | Out-String).Trim()
        if (-not $cid) { return $false }
        $health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $cid 2>$null | Out-String).Trim()
        return $LASTEXITCODE -eq 0 -and $health -eq 'healthy'
    } catch {
        return $false
    }
}
function Assert-ComposeServiceHealthy {
    param([Parameter(Mandatory=$true)][string]$Service)
    if (Test-ComposeServiceHealthy $Service) { return }

    $cid = ''
    try { $cid = ((Invoke-ComposeCapture ps -q $Service) | Out-String).Trim() } catch {}
    if (-not $cid) {
        try { Invoke-Compose ps } catch {}
        throw "Service $Service is not running: no container exists. Run '.\docker\dev-compose.ps1 up' successfully before smoke/ha-smoke and resolve any build/start failure first."
    }

    $health = 'unknown'
    try {
        $health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $cid 2>&1 | Out-String).Trim()
    } catch {}
    try { Invoke-Compose ps } catch {}
    try { Invoke-Compose logs --no-color --tail=200 $Service } catch {}
    throw "Service $Service is not healthy (container=$cid health=$health)"
}
function Invoke-DatabaseScalar {
    param([Parameter(Mandatory=$true)][string]$Sql)
    $shell = 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; exec psql --no-psqlrc --tuples-only --no-align --host=postgres --port=5432 --username=infranexum --dbname=infranexum --command "$INFRANEXUM_SQL"'
    return ((Invoke-ComposeCapture run --rm --no-deps -e "INFRANEXUM_SQL=$Sql" --entrypoint /bin/sh migrate -eu -c $shell) | Out-String).Trim()
}
function Invoke-ClusterDatabaseAdminScalar {
    param([Parameter(Mandatory=$true)][string]$Sql)
    # Cluster-wide diagnostics (for example pg_stat_replication) intentionally use
    # the postgres maintenance database. Do not use this helper for application
    # schemas: PostgreSQL object namespaces are database-local.
    $shell = 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; exec psql --no-psqlrc --tuples-only --no-align --host=postgres --port=5432 --username=postgres --dbname=postgres --command "$INFRANEXUM_SQL"'
    return ((Invoke-ComposeCapture run --rm --no-deps -e "INFRANEXUM_SQL=$Sql" --entrypoint /bin/sh migrate -eu -c $shell) | Out-String).Trim()
}
function Invoke-ApplicationDatabaseAdminScalar {
    param([Parameter(Mandatory=$true)][string]$Sql)
    # Schema/history diagnostics require superuser visibility but must connect to
    # the InfraNexum application database where those schemas physically exist.
    $shell = 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; exec psql --no-psqlrc --tuples-only --no-align --host=postgres --port=5432 --username=postgres --dbname=infranexum --command "$INFRANEXUM_SQL"'
    return ((Invoke-ComposeCapture run --rm --no-deps -e "INFRANEXUM_SQL=$Sql" --entrypoint /bin/sh migrate -eu -c $shell) | Out-String).Trim()
}

function Get-LocalDeveloperPassword {
    $password = ((Invoke-ComposeCapture run --rm --no-deps --entrypoint /bin/sh secret-init -eu -c 'cat /run/infranexum-secrets/local-admin-password') | Out-String).Trim()
    if (-not $password) { throw 'Local administrator bootstrap credential is unavailable' }
    return $password
}
function Show-LocalDeveloperCredentials {
    Assert-Repository
    # The secret remains in the developer-only named volume. It is disclosed only
    # on an explicit operator command and is never copied into images or logs.
    $password = Get-LocalDeveloperPassword
    Write-Output 'InfraNexum local development administrator'
    Write-Output 'Username: admin'
    Write-Output "Password: $password"
    Write-Output 'The password must be changed at first sign-in.'
}
function Test-LocalCredentialLogin {
    param([Parameter(Mandatory=$true)][int]$WebPort)
    $mustChange = [int](Invoke-ApplicationDatabaseAdminScalar "SELECT count(*) FROM infranexum_iam.local_account WHERE username='admin' AND must_change=TRUE AND status='ACTIVE'")
    if ($mustChange -eq 0) { return 'SKIPPED_CHANGED' }

    $password = Get-LocalDeveloperPassword
    $uri = "http://127.0.0.1:$WebPort/api/v1/iam/local-auth/session"
    $webSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $payload = @{ username='admin'; password=$password } | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -Uri $uri -Method Post -ContentType 'application/json' -Headers @{Accept='application/json'} -Body $payload -WebSession $webSession -TimeoutSec 15 -SkipHttpErrorCheck
    if ([int]$response.StatusCode -ne 200) {
        $safeBody = Convert-WebResponseContentToText $response.Content
        throw "Bootstrap credential login failed through Web ingress with HTTP $([int]$response.StatusCode); body='$safeBody'"
    }
    $body = (Convert-WebResponseContentToText $response.Content) | ConvertFrom-Json -ErrorAction Stop
    if ($body.username -ne 'admin' -or $body.mustChange -ne $true) { throw 'Bootstrap credential login returned an unexpected session payload' }

    $cookies = $webSession.Cookies.GetCookies([Uri]$uri)
    $sessionCookie = $cookies['INX_SESSION']
    $csrfCookie = $cookies['INX_XSRF']
    if ($null -eq $sessionCookie -or [string]::IsNullOrWhiteSpace($sessionCookie.Value)) { throw 'Bootstrap credential login did not issue INX_SESSION' }
    if ($null -eq $csrfCookie -or [string]::IsNullOrWhiteSpace($csrfCookie.Value)) { throw 'Bootstrap credential login did not issue INX_XSRF' }

    $logout = Invoke-WebRequest -Uri $uri -Method Delete -Headers @{'X-CSRF-Token'=$csrfCookie.Value} -WebSession $webSession -TimeoutSec 15 -SkipHttpErrorCheck
    if ([int]$logout.StatusCode -ne 204) { throw "Bootstrap credential smoke logout returned HTTP $([int]$logout.StatusCode)" }
    return 'PASS'
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
    $writer=Get-PublishedPort postgres 5432; $reader=Get-PublishedPort postgres 5433; $port=Get-PublishedPort server 8080; $webPort=Get-PublishedPort web 8080
    Write-Output "Compose PRO bindings: writer=127.0.0.1:$writer replicas=127.0.0.1:$reader server=127.0.0.1:$port web=127.0.0.1:$webPort"
    $streaming=[int](Invoke-ClusterDatabaseAdminScalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'"); if ($streaming -lt 2) { throw "Expected two streaming standbys; observed $streaming" }
    $sync=[int](Invoke-ClusterDatabaseAdminScalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state IN ('sync','quorum')"); if ($sync -lt 1) { throw 'No synchronous PostgreSQL standby' }
    $ready=Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health/readiness" -TimeoutSec 10; if ($ready.status -ne 'UP') { throw 'Server router readiness is not UP' }
    $metric=Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/metrics/infranexum.workers.ready" -TimeoutSec 10; if ($metric.name -ne 'infranexum.workers.ready') { throw 'Workers metric unavailable' }
    $cid='018bcfe5-6800-7001-8000-000000000001'; $response=Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/v1/system/build" -Headers @{'X-Correlation-ID'=$cid} -TimeoutSec 10
    $build=$response.Content | ConvertFrom-Json; if ($build.instanceId -notmatch '^server-pro-[1-4]$') { throw "Unexpected routed instance $($build.instanceId)" }; if ($response.Headers['X-Correlation-ID'] -ne $cid) { throw 'Correlation was not propagated' }
    $webReady=Invoke-RestMethod -Uri "http://127.0.0.1:$webPort/health/ready" -TimeoutSec 10; if ($webReady.status -ne 'UP') { throw 'Web router readiness is not UP' }
    $runtime=Invoke-RestMethod -Uri "http://127.0.0.1:$webPort/runtime-config.json" -TimeoutSec 10; if ($runtime.component -ne 'web' -or $runtime.version -ne '2.0.0-alpha.0.94' -or $runtime.apiBaseUrl -ne '/api') { throw 'Web runtime configuration is inconsistent with Compose bindings' }
    $iamHistory=[int](Invoke-ApplicationDatabaseAdminScalar "SELECT count(*) FROM infranexum_core.schema_history WHERE migration_id IN ('0011','0012','0013')")
    if ($iamHistory -ne 3) { throw "IAM migration history is incomplete; expected 0011, 0012 and 0013, observed $iamHistory" }
    $accountTable=[int](Invoke-ApplicationDatabaseAdminScalar "SELECT CASE WHEN to_regclass('infranexum_iam.local_account') IS NOT NULL THEN 1 ELSE 0 END")
    if ($accountTable -ne 1) { throw 'Local identity account table is missing after repair migration 0012' }
    $sessionTable=[int](Invoke-ApplicationDatabaseAdminScalar "SELECT CASE WHEN to_regclass('infranexum_iam.local_session') IS NOT NULL THEN 1 ELSE 0 END")
    if ($sessionTable -ne 1) { throw 'Local identity session table is missing after repair migration 0012' }
    $accountCount=[int](Invoke-ApplicationDatabaseAdminScalar "SELECT count(*) FROM infranexum_iam.local_account")
    if ($accountCount -lt 1) { throw 'Local identity bootstrap account is missing after schema repair and Server bootstrap' }
    $rbacUserTable=[int](Invoke-ApplicationDatabaseAdminScalar "SELECT CASE WHEN to_regclass('infranexum_iam.iam_user') IS NOT NULL THEN 1 ELSE 0 END")
    if ($rbacUserTable -ne 1) { throw 'RBAC IAM user table is missing after migration 0013' }
    $platformAdminCount=[int](Invoke-ApplicationDatabaseAdminScalar "SELECT count(*) FROM infranexum_iam.role_assignment ra JOIN infranexum_iam.role r ON r.id=ra.role_id WHERE ra.actor_type='USER' AND ra.scope_kind='PLATFORM' AND ra.revoked_at IS NULL AND r.code='system.platform_admin' AND r.system_role=TRUE AND r.active=TRUE AND r.deleted_at IS NULL")
    if ($platformAdminCount -lt 1) { throw 'RBAC bootstrap platform administrator assignment is missing after migration 0013/Server bootstrap' }
    $credentialLogin = Test-LocalCredentialLogin -WebPort $webPort
    # PowerShell 7 can preserve HTTP error responses as ordinary response objects.
    # This avoids the different header representation exposed through exception
    # objects and lets the smoke validate status, response header and problem body
    # through the same Web ingress path.
    $anonymousResponse = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$webPort/api/v1/iam/organizations?limit=1" `
        -Headers @{'X-Correlation-ID'=$cid} `
        -TimeoutSec 10 `
        -SkipHttpErrorCheck
    if ([int]$anonymousResponse.StatusCode -ne 401) {
        throw "Protected Organization API returned HTTP $([int]$anonymousResponse.StatusCode) to an anonymous request"
    }
    $anonymousCorrelation = (@($anonymousResponse.Headers['X-Correlation-ID']) | ForEach-Object { [string]$_ }) -join ','
    if ($anonymousCorrelation.Trim() -ne $cid) {
        throw "Authentication boundary did not preserve correlation through Web ingress; expected $cid, observed '$anonymousCorrelation'"
    }
    $anonymousRawBody = Convert-WebResponseContentToText $anonymousResponse.Content
    try {
        $anonymousProblem = $anonymousRawBody | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Authentication rejection body is not valid JSON; HTTP $([int]$anonymousResponse.StatusCode); body='$anonymousRawBody'"
    }
    $problemCorrelationProperty = $anonymousProblem.PSObject.Properties['correlation_id']
    $problemCorrelation = if ($null -eq $problemCorrelationProperty) { '' } else { [string]$problemCorrelationProperty.Value }
    if ($problemCorrelation -ne $cid) {
        throw "Authentication problem body did not preserve correlation through Web ingress; expected $cid, observed '$problemCorrelation'; body='$anonymousRawBody'"
    }
    Write-Output "compose-smoke: PASS (streaming=$streaming synchronous=$sync Server=4 Web=2 LocalAuth=ENFORCED CredentialLogin=$credentialLogin)"
}
function Get-PatroniPrimaryService {
    foreach ($service in @('postgres-1','postgres-2','postgres-3')) {
        try { $code=((Invoke-ComposeCapture exec -T $service sh -c 'curl --silent --head --output /dev/null --write-out "%{http_code}" http://127.0.0.1:8008/primary') | Out-String).Trim(); if ($code -eq '200') { return $service } } catch {}
    }; throw 'Unable to identify Patroni primary'
}
function Wait-DatabaseWriterReady {
    param(
        [ValidateRange(1, 300)][int]$TimeoutSeconds = 60,
        [ValidateRange(1, 30)][int]$PollSeconds = 2
    )
    # Patroni leadership can converge before HAProxy has promoted the new writer
    # backend through its health-check rise threshold. Probe only the idempotent
    # SELECT 1 readiness check, bound the wait, and preserve the last diagnostic.
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastDiagnostic = 'no writer probe completed'
    do {
        try {
            $value = Invoke-DatabaseScalar 'SELECT 1'
            if ($value -eq '1') { return }
            $lastDiagnostic = "unexpected scalar result: $value"
        } catch {
            $lastDiagnostic = $_.Exception.Message
        }
        if ([DateTime]::UtcNow -lt $deadline) { Start-Sleep -Seconds $PollSeconds }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Writer endpoint did not recover within $TimeoutSeconds seconds. Last diagnostic: $lastDiagnostic"
}
function Wait-HttpJsonEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][scriptblock]$Accept,
        [ValidateRange(1, 300)][int]$TimeoutSeconds = 60,
        [ValidateRange(1, 30)][int]$PollSeconds = 2
    )
    # HAProxy backend membership converges asynchronously after an upstream
    # dependency or node changes state. Retry only idempotent HTTP GET probes,
    # keep the wait bounded, and preserve the final 5xx/transport diagnostic.
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastDiagnostic = 'no HTTP probe completed'
    do {
        try {
            $response = Invoke-RestMethod -Uri $Uri -TimeoutSec 10
            if (& $Accept $response) { return $response }
            $lastDiagnostic = "endpoint returned an unexpected payload: $($response | ConvertTo-Json -Compress -Depth 5)"
        } catch {
            $lastDiagnostic = $_.Exception.Message
        }
        if ([DateTime]::UtcNow -lt $deadline) { Start-Sleep -Seconds $PollSeconds }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Label did not recover within $TimeoutSeconds seconds. Last diagnostic: $lastDiagnostic"
}
function Wait-ServerRouterReady {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [ValidateRange(1, 300)][int]$TimeoutSeconds = 60,
        [ValidateRange(1, 30)][int]$PollSeconds = 2
    )
    [void](Wait-HttpJsonEndpoint -Uri "http://127.0.0.1:$Port/actuator/health/readiness" -Label 'Server router readiness' -TimeoutSeconds $TimeoutSeconds -PollSeconds $PollSeconds -Accept { param($response) $response.status -eq 'UP' })
}
function Wait-WebRouterReady {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [ValidateRange(1, 300)][int]$TimeoutSeconds = 60,
        [ValidateRange(1, 30)][int]$PollSeconds = 2
    )
    [void](Wait-HttpJsonEndpoint -Uri "http://127.0.0.1:$Port/health/ready" -Label 'Web router readiness' -TimeoutSeconds $TimeoutSeconds -PollSeconds $PollSeconds -Accept { param($response) $response.status -eq 'UP' })
}
function Invoke-HaSmoke {
    Assert-Repository; Invoke-Smoke; $haStartedAt=[DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'); $primary=Get-PatroniPrimaryService; Write-Output "Stopping current Patroni primary: $primary"; Invoke-Compose stop $primary
    try {
        $deadline=[DateTime]::UtcNow.AddSeconds(60); $replacement=$null
        do { Start-Sleep 2; try { $replacement=Get-PatroniPrimaryService } catch { $replacement=$null } } while (-not $replacement -and [DateTime]::UtcNow -lt $deadline)
        if (-not $replacement -or $replacement -eq $primary) { throw 'No replacement primary within 60 seconds' }
        Wait-DatabaseWriterReady -TimeoutSeconds 60 -PollSeconds 2
        $port=Get-PublishedPort server 8080; Wait-ServerRouterReady -Port $port -TimeoutSeconds 60 -PollSeconds 2
    } finally { Invoke-Compose start $primary }
    $deadline=[DateTime]::UtcNow.AddSeconds(90); $healthy=$false
    do { Start-Sleep 3; $healthy = Test-ComposeServiceHealthy $primary } while (-not $healthy -and [DateTime]::UtcNow -lt $deadline)
    if (-not $healthy) { throw "Former primary $primary did not rejoin within 90 seconds" }
    $deadline=[DateTime]::UtcNow.AddSeconds(90); $streaming=0
    do { Start-Sleep 3; try { $streaming=[int](Invoke-ClusterDatabaseAdminScalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'") } catch { $streaming=0 } } while ($streaming -lt 2 -and [DateTime]::UtcNow -lt $deadline)
    if ($streaming -lt 2) { throw "Cluster did not return to two streaming standbys; observed $streaming" }

    $serverPort=Get-PublishedPort server 8080; Write-Output 'Stopping Server node server-1'; Invoke-Compose stop server-1
    try {
        Wait-ServerRouterReady -Port $serverPort -TimeoutSeconds 60 -PollSeconds 2
    } finally { Invoke-Compose start server-1 }
    $deadline=[DateTime]::UtcNow.AddSeconds(60); $serverHealthy=$false
    do { Start-Sleep 2; $serverHealthy = Test-ComposeServiceHealthy server-1 } while (-not $serverHealthy -and [DateTime]::UtcNow -lt $deadline)
    if (-not $serverHealthy) { throw 'Server node server-1 did not rejoin within 60 seconds' }

    $webPort=Get-PublishedPort web 8080; Write-Output 'Stopping Web node web-1'; Invoke-Compose stop web-1
    try {
        Wait-WebRouterReady -Port $webPort -TimeoutSeconds 60 -PollSeconds 2
        [void](Wait-HttpJsonEndpoint -Uri "http://127.0.0.1:$webPort/runtime-config.json" -Label 'Web runtime configuration' -TimeoutSeconds 60 -PollSeconds 2 -Accept { param($response) $response.component -eq 'web' })
    } finally { Invoke-Compose start web-1 }
    $deadline=[DateTime]::UtcNow.AddSeconds(60); $webHealthy=$false
    do { Start-Sleep 2; $webHealthy = Test-ComposeServiceHealthy web-1 } while (-not $webHealthy -and [DateTime]::UtcNow -lt $deadline)
    if (-not $webHealthy) { throw 'Web node web-1 did not rejoin within 60 seconds' }

    $patroniLogs = ((Invoke-ComposeCapture logs --no-color --since $haStartedAt postgres-1 postgres-2 postgres-3) | Out-String)
    if ($patroniLogs -match '(?m)(Traceback \(most recent call last\):|ConnectionResetError:|BrokenPipeError:)') {
        $diagnostic = (($patroniLogs -split "`r?`n") | Where-Object { $_ -match 'Traceback \(most recent call last\):|ConnectionResetError:|BrokenPipeError:' } | Select-Object -First 12) -join [Environment]::NewLine
        throw "Patroni REST API emitted Python transport tracebacks during HA smoke. Health checks must not terminate response bodies early. Diagnostic:$([Environment]::NewLine)$diagnostic"
    }

    Write-Output "compose-ha-smoke: PASS (PostgreSQL $primary -> $replacement -> rejoined; Server and Web node failover verified; PatroniPythonErrors=0)"
}

switch ($Command) {
 'config' { Assert-Repository; Invoke-Compose config --quiet }
 'build' { Assert-Repository; Invoke-Compose config --quiet; Invoke-Compose build --pull }
 'up' { Assert-Repository; Invoke-Compose config --quiet; try { Invoke-Compose rm --stop --force migrate db-bootstrap secret-init } catch {}; Invoke-Compose up --detach --build --wait web }
 'down' { Assert-Repository; Invoke-Compose down --remove-orphans }
 'logs' { Assert-Repository; if ($Services.Count) { Invoke-Compose logs --no-color --tail=200 @Services } else { Invoke-Compose logs --no-color --tail=200 web web-1 web-2 server server-1 server-2 server-3 server-4 postgres postgres-1 postgres-2 postgres-3 migrate } }
 'smoke' { Invoke-Smoke }
 'ha-smoke' { Invoke-HaSmoke }
 'credentials' { Show-LocalDeveloperCredentials }
 'backup' { New-DatabaseBackup }
 'restore' { Assert-Repository; if ($env:CONFIRM_INFRANEXUM_RESTORE -ne 'YES') { throw 'Refusing restore; set CONFIRM_INFRANEXUM_RESTORE=YES' }; if (-not $env:BACKUP_FILE) { throw 'BACKUP_FILE is required' }; $backup=(Resolve-Path $env:BACKUP_FILE).Path; Invoke-Compose up --detach --wait postgres; Invoke-Compose --profile maintenance up --detach db-admin; try { Invoke-Compose stop web web-1 web-2 server server-1 server-2 server-3 server-4 } catch {}; $remote='/tmp/infranexum-dev-restore.dump'; Invoke-Compose cp $backup "db-admin:$remote"; Invoke-Compose exec -T db-admin sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; dropdb --if-exists --host=postgres --port=5432 --username=infranexum --maintenance-db=postgres infranexum; createdb --host=postgres --port=5432 --username=infranexum --maintenance-db=postgres --owner=infranexum infranexum; pg_restore --exit-on-error --no-owner --no-privileges --host=postgres --port=5432 --username=infranexum --dbname=infranexum /tmp/infranexum-dev-restore.dump'; Invoke-Compose exec -T db-admin rm -f $remote; Invoke-Compose stop db-admin; Invoke-Compose run --rm migrate; Invoke-Compose up --detach --wait web }
 'rollback' { Assert-Repository; if (-not $env:MIGRATION_ID -or $env:CONFIRM_INFRANEXUM_ROLLBACK -ne 'YES') { throw 'MIGRATION_ID and CONFIRM_INFRANEXUM_ROLLBACK=YES are required' }; Invoke-Compose up --detach --wait postgres; $backup=New-DatabaseBackup; Write-Output "Pre-rollback backup: $backup"; try { Invoke-Compose stop web web-1 web-2 server server-1 server-2 server-3 server-4 } catch {}; Invoke-Compose --profile maintenance run --rm -e "MIGRATION_ID=$($env:MIGRATION_ID)" -e CONFIRM_INFRANEXUM_ROLLBACK=YES rollback }
 'reset' { Assert-Repository; if ($env:CONFIRM_INFRANEXUM_VOLUME_DELETE -ne 'YES') { throw 'Refusing volume deletion' }; Invoke-Compose down --volumes --remove-orphans }
 'help' { Write-Output 'Commands: config build up down logs smoke ha-smoke credentials backup restore rollback reset' }
}
