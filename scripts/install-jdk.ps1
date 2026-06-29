param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$ToolsDir = Join-Path $ProjectRoot 'tools'
$JdkDir = Join-Path $ToolsDir 'jdk-21'
$Javac = Join-Path $JdkDir 'bin\javac.exe'

if (Test-Path -LiteralPath $Javac) {
    Write-Host "Using local JDK: $JdkDir"
    exit 0
}

New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
$ZipPath = Join-Path $ToolsDir 'temurin-jdk-21.zip'
$ExtractDir = Join-Path $ToolsDir 'jdk-download'
$DownloadUrl = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'

Write-Host 'Downloading portable JDK 21 into project tools directory...'
Invoke-WebRequest -Uri $DownloadUrl -OutFile $ZipPath

if (Test-Path -LiteralPath $ExtractDir) {
    Remove-Item -LiteralPath $ExtractDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $ExtractDir | Out-Null
Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractDir -Force

$DownloadedJavac = Get-ChildItem -LiteralPath $ExtractDir -Recurse -Filter 'javac.exe' | Select-Object -First 1
if (-not $DownloadedJavac) {
    throw 'Downloaded JDK archive does not contain javac.exe'
}

if (Test-Path -LiteralPath $JdkDir) {
    Remove-Item -LiteralPath $JdkDir -Recurse -Force
}
Move-Item -LiteralPath $DownloadedJavac.Directory.Parent.FullName -Destination $JdkDir
Remove-Item -LiteralPath $ExtractDir -Recurse -Force
Remove-Item -LiteralPath $ZipPath -Force

Write-Host "Local JDK installed: $JdkDir"
