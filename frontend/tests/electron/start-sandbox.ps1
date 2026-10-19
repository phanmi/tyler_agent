# Resolve mappings after extraction so the handoff can move between machines.
$ErrorActionPreference = 'Stop'
$sandbox = Join-Path $env:windir 'System32/WindowsSandbox.exe'
if (-not (Test-Path -LiteralPath $sandbox)) { throw 'Enable Windows Sandbox, or run inputs\run.cmd inside an offline Windows VM.' }
$inputs = [Security.SecurityElement]::Escape((Join-Path $PSScriptRoot 'inputs'))
$resultsPath = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Path $resultsPath -Force | Out-Null
$results = [Security.SecurityElement]::Escape($resultsPath)
@"
<Configuration>
  <Networking>Disable</Networking>
  <MappedFolders>
    <MappedFolder><HostFolder>$inputs</HostFolder><SandboxFolder>C:\SmokeInputs</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$results</HostFolder><SandboxFolder>C:\SmokeResults</SandboxFolder><ReadOnly>false</ReadOnly></MappedFolder>
  </MappedFolders>
  <LogonCommand><Command>cmd.exe /c C:\SmokeInputs\run.cmd C:\SmokeResults</Command></LogonCommand>
</Configuration>
"@ | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'Tyler.wsb') -Encoding utf8
Start-Process -FilePath $sandbox -ArgumentList ('"' + (Join-Path $PSScriptRoot 'Tyler.wsb') + '"') -WindowStyle Hidden
