# Run the project's Maven Wrapper with Java 21 without changing global settings.
$ErrorActionPreference = 'Stop'
$jdkCandidates = @(
    $env:YUFEICHI_JAVA_HOME,
    (Join-Path $env:USERPROFILE '.jdks\temurin-21.0.11'),
    $env:JAVA_HOME
)
$projectJdk = $null
foreach ($candidate in $jdkCandidates) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'release'))) {
        $release = Get-Content -LiteralPath (Join-Path $candidate 'release') -Raw
        if ($release -match '(?m)^JAVA_VERSION="21(?:\.|"|-)') {
            $projectJdk = $candidate
            break
        }
    }
}
if (-not $projectJdk) {
    throw 'Java 21 is required. Set YUFEICHI_JAVA_HOME to your JDK 21 directory.'
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
Push-Location (Join-Path $PSScriptRoot '..\yufeichi-server')
try {
    $env:JAVA_HOME = $projectJdk
    $env:Path = (Join-Path $projectJdk 'bin') + ';' + $previousPath
    & .\mvnw.cmd @args
    $mavenExitCode = $LASTEXITCODE
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
    Pop-Location
}
exit $mavenExitCode
