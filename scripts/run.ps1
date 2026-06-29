param(
    [int]$Port = 8080,
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1') -ProjectRoot $ProjectRoot

$Java = Join-Path $ProjectRoot 'tools\jdk-21\bin\java.exe'
if (-not (Test-Path -LiteralPath $Java)) {
    $Java = 'java'
}

Push-Location $ProjectRoot
try {
    & $Java -cp (Join-Path $ProjectRoot 'build\classes') minishop.Main $Port
}
finally {
    Pop-Location
}
