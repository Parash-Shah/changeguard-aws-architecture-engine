param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
elseif (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA\jbr" }
# Use Windows' trusted certificate store in this workstation environment; TLS validation stays enabled.
$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$mavenPath = if ($mavenCommand) { $mavenCommand.Source } else { "$env:LOCALAPPDATA\Programs\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" }
$ErrorActionPreference = 'Continue'
& $mavenPath '-B' "-Dmaven.repo.local=$projectRoot\.m2\repository" @MavenArgs
exit $LASTEXITCODE
