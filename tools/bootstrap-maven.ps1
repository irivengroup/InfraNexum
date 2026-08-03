
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Properties = Join-Path $Root ".mvn\wrapper\maven-wrapper.properties"
$Cache = if ($env:MAVEN_WRAPPER_CACHE) { $env:MAVEN_WRAPPER_CACHE } else { Join-Path $HOME ".m2\wrapper\dists" }

$JavaVersion = (& java -version 2>&1 | Select-Object -First 1)
if ($JavaVersion -notmatch 'version "25(?:\.|\")') {
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
}

& $Maven @args
exit $LASTEXITCODE
