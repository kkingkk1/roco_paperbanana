param(
  [Parameter(Mandatory = $true)][string]$Scene,
  [Parameter(Mandatory = $true)][ValidateRange(0, 2147483647)][int]$Tasks,
  [Parameter(Mandatory = $true)][ValidateRange(1, 2147483647)][int]$Satellites,
  [Parameter(Mandatory = $true)][ValidatePattern('^A[123]B[123]C[123]$')][string]$Combination,
  [Parameter(Mandatory = $true)][long]$Seed
)

$ErrorActionPreference = 'Stop'
$workerScript = Join-Path $PSScriptRoot 'run-manual-worker.ps1'
& pwsh -NoProfile -NonInteractive -File $workerScript `
  -Scene $Scene -Tasks $Tasks -Satellites $Satellites -Combination $Combination -Seed $Seed
$runExitCode = $LASTEXITCODE
if ($runExitCode -ne 0) { exit $runExitCode }
