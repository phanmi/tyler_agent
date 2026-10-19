# Prepare an offline, disposable Windows Sandbox check. Run on a host with Windows Sandbox enabled.
param([string]$OutputRoot = 'target/clean-windows')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$destination = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputRoot))
if (-not $destination.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw 'Sandbox staging must be inside this checkout'
}
if (Test-Path -LiteralPath $destination) { throw 'Choose a new OutputRoot; existing evidence is preserved' }
$inputs = Join-Path $destination 'inputs'
$results = Join-Path $destination 'results'
New-Item -ItemType Directory -Path $inputs, $results | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'frontend/out/Tyler-win32-x64') -Destination (Join-Path $inputs 'artifact') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'agent-python/dist/desktop-test-worker') -Destination (Join-Path $inputs 'fixture') -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'desktop-smoke.cjs') -Destination $inputs
Copy-Item -LiteralPath (Join-Path $repoRoot 'frontend/out/make/squirrel.windows/x64') -Destination (Join-Path $inputs 'installer') -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'offline-run.cmd') -Destination (Join-Path $inputs 'run.cmd')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-sandbox.ps1') -Destination (Join-Path $destination 'Start-Sandbox.ps1')
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/DESKTOP_PACKAGING.md') -Destination (Join-Path $destination 'README.md')
$checksums = Get-ChildItem -LiteralPath $inputs -File -Recurse | Get-FileHash -Algorithm SHA256 | ForEach-Object {
    $_.Hash.ToLowerInvariant() + '  ' + $_.Path.Substring($destination.Length + 1)
}
$checksums | Set-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt') -Encoding ascii
Write-Output "Prepared $destination. Run Start-Sandbox.ps1 on the clean-machine host, or inputs\run.cmd inside an offline VM."
