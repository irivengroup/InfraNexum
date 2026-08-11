$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Properties = Join-Path $Root ".mvn\wrapper\maven-wrapper.properties"
$Cache = if ($env:MAVEN_WRAPPER_CACHE) { $env:MAVEN_WRAPPER_CACHE } else { Join-Path $HOME ".m2\wrapper\dists" }

# java -version writes its normal version banner to stderr. Invoke it through
# System.Diagnostics.Process so PowerShell never promotes that expected stderr
# output to NativeCommandError when $ErrorActionPreference is Stop.
$JavaStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
$JavaStartInfo.FileName = "java"
$JavaStartInfo.Arguments = "-version"
$JavaStartInfo.UseShellExecute = $false
$JavaStartInfo.RedirectStandardOutput = $true
$JavaStartInfo.RedirectStandardError = $true
$JavaProcess = [System.Diagnostics.Process]::new()
$JavaProcess.StartInfo = $JavaStartInfo
try {
    if (-not $JavaProcess.Start()) {
        throw "Unable to start java for toolchain validation."
    }
    $JavaStdout = $JavaProcess.StandardOutput.ReadToEnd()
    $JavaStderr = $JavaProcess.StandardError.ReadToEnd()
    $JavaProcess.WaitForExit()
    if ($JavaProcess.ExitCode -ne 0) {
        throw "java -version failed with exit code $($JavaProcess.ExitCode)."
    }
} finally {
    $JavaProcess.Dispose()
}
$JavaVersion = (($JavaStderr + "`n" + $JavaStdout) -split "`r?`n" | Where-Object { $_ -match '\S' } | Select-Object -First 1)
if (-not $JavaVersion -or $JavaVersion -notmatch 'version "25(?:\.|\")') {
    throw "InfraNexum requires JDK 25. Detected: $JavaVersion"
}

$Map = @{}
Get-Content $Properties | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $Map[$Matches[1]] = $Matches[2] }
}
$Url = $Map['distributionUrl']
$Expected = $Map['distributionSha512Sum'].ToLowerInvariant()
if ($Url -notmatch 'apache-maven-([0-9.]+)-bin.tar.gz$') { throw "Invalid Maven distribution URL." }
$Version = $Matches[1]
$Destination = Join-Path $Cache "apache-maven-$Version"
$Archive = Join-Path $Cache "apache-maven-$Version-bin.tar.gz"
$Maven = Join-Path $Destination "bin\mvn.cmd"

New-Item -ItemType Directory -Force -Path $Cache | Out-Null
if (-not (Test-Path $Maven)) {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Archive
    $Actual = (Get-FileHash -Algorithm SHA512 -Path $Archive).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected) {
        Remove-Item -Force $Archive
        throw "Maven archive checksum mismatch."
    }
    if (Test-Path $Destination) { Remove-Item -Recurse -Force $Destination }
    tar -xzf $Archive -C $Cache
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to extract Maven distribution."
    }
}

& $Maven @args
exit $LASTEXITCODE
