$repo = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem (Join-Path $repo ".tools") -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if ($env:JAVA_HOME) {
    $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    $maven = Get-ChildItem (Join-Path $repo ".tools") -Directory -Filter "apache-maven-*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($maven) { $env:Path = (Join-Path $maven.FullName "bin") + ";" + $env:Path }
}
if (-not (Get-Command java -ErrorAction SilentlyContinue) -or -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "JDK 17+ and Maven are required. Install them, or keep the portable copies under .tools."
    exit 1
}
Set-Location $PSScriptRoot
mvn spring-boot:run
