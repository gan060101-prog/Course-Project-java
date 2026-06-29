param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$matches = Get-CimInstance Win32_Process |
    Where-Object {
        $_.Name -eq 'java.exe' -and
        $_.CommandLine -like '*minishop.Main*' -and
        $_.CommandLine -like "*$ProjectRoot*"
    }

if (-not $matches) {
    Write-Host 'MiniShop service is not running for this project.'
    exit 0
}

foreach ($process in $matches) {
    Stop-Process -Id $process.ProcessId -Force
    Write-Host "Stopped MiniShop process: $($process.ProcessId)"
}
