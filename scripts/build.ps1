param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'install-jdk.ps1') -ProjectRoot $ProjectRoot

$Javac = Join-Path $ProjectRoot 'tools\jdk-21\bin\javac.exe'
if (-not (Test-Path -LiteralPath $Javac)) {
    $Javac = 'javac'
}

$BuildDir = Join-Path $ProjectRoot 'build\classes'
if (Test-Path -LiteralPath $BuildDir) {
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

$Sources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $Sources) {
    throw 'No Java source files found.'
}

& $Javac -encoding UTF-8 -d $BuildDir $Sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}
Write-Host "Build completed: $BuildDir"
