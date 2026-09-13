param(
    [ValidateSet('Discover', 'Review', 'Milestones')][string]$Mode = 'Discover',
    [string]$Profile = 'changeguard',
    [string]$Region = 'us-east-1',
    [string]$WorkloadId,
    [string]$RunToken
)
$ErrorActionPreference = 'Stop'
if ($Mode -ne 'Discover' -and [string]::IsNullOrWhiteSpace($WorkloadId)) {
    throw 'Review and Milestones modes require -WorkloadId.'
}
if ($Mode -eq 'Milestones' -and [string]::IsNullOrWhiteSpace($RunToken)) {
    throw 'Milestones mode requires a stable -RunToken; reuse it for retries.'
}
$projectRoot = Split-Path $PSScriptRoot -Parent
$probeJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
$probeJava = if ($probeJdk) { Join-Path $probeJdk.FullName 'bin/java.exe' }
    elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' }
    else { throw 'Install Java 21 and set JAVA_HOME, or use the workstation JDK under .tools.' }
$env:AWS_PROFILE = $Profile
$env:AWS_REGION = $Region
$env:CHANGEGUARD_AWS_WORKLOAD_ID = $WorkloadId
$env:CHANGEGUARD_AWS_RUN_TOKEN = $RunToken
$probeArguments = '-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE -classpath %classpath com.changeguard.WellArchitectedLiveProbe'
if ($Mode -eq 'Discover') { $probeArguments += ' --list-workloads' }
if ($Mode -eq 'Milestones') { $probeArguments += ' --create-milestones' }
Push-Location -LiteralPath $projectRoot
try {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'local-maven.ps1') test-compile `
        org.codehaus.mojo:exec-maven-plugin:3.5.0:exec "-Dexec.executable=$probeJava" '-Dexec.classpathScope=test' "-Dexec.args=$probeArguments"
    $probeExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $probeExitCode
