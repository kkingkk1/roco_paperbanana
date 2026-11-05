param(
  [Parameter(Mandatory = $true)][ValidateSet('native', 'generic')][string]$Implementation,
  [Parameter(Mandatory = $true)][int]$Tasks,
  [Parameter(Mandatory = $true)][long]$Seed,
  [Parameter(Mandatory = $true)][int]$ReassignIterations,
  [Parameter(Mandatory = $true)][ValidateSet('true', 'false', 'default')][string]$Deterministic,
  [ValidateSet('1', 'default')][string]$SatellitePool = '1',
  [Parameter(Mandatory = $true)][string]$Output
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath '.').ProviderPath
$outputPath = [IO.Path]::GetFullPath($Output, $projectRoot)
$outputsRoot = (Resolve-Path -LiteralPath 'outputs').ProviderPath
if (-not $outputPath.StartsWith($outputsRoot + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be inside outputs/' }
if (Test-Path -LiteralPath $outputPath) { throw "Output already exists: $outputPath" }

Add-Type -Path (Join-Path $projectRoot 'scripts/P1ResourceJob.cs')
$localProcess = Get-Process -Id $PID
$previousAffinity = $localProcess.ProcessorAffinity
$availableMask = $previousAffinity.ToInt64()
$chosenMask = [long]1
while (($availableMask -band $chosenMask) -eq 0) {
  $chosenMask = $chosenMask -shl 1
  if ($chosenMask -eq 0) { throw 'No available CPU affinity bit' }
}

$javaProcess = $null
try {
  $localProcess.ProcessorAffinity = [IntPtr]$chosenMask
  if ($localProcess.ProcessorAffinity.ToInt64() -ne $chosenMask) { throw 'CPU affinity was not applied' }
  [P1ResourceJob]::Attach([UInt64]2147483648)
  $start = [System.Diagnostics.ProcessStartInfo]::new()
  $start.FileName = (Get-Command java -CommandType Application).Source
  $start.WorkingDirectory = $projectRoot
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardInput = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $classes = Join-Path $projectRoot 'target/p1-rkhma-unified/classes'
  $fixtureClasses = Join-Path $projectRoot 'target/p1-rkhma-phase-check/classes'
  $classPath = "$classes;$fixtureClasses;$(Join-Path $projectRoot 'target/classes')"
  $mainClass = 'UnifiedRkhmaAudit'
  foreach ($argument in @('-Xms64m', '-Xmx2g', '-XX:ActiveProcessorCount=1')) {
    [void]$start.ArgumentList.Add($argument)
  }
  if ($SatellitePool -eq '1') { [void]$start.ArgumentList.Add('-Dautohyb.parallel.satellitePoolSize=1') }
  if ($Deterministic -ne 'default') { [void]$start.ArgumentList.Add("-Dautohyb.deterministic=$Deterministic") }
  $scene = 'E:\OneDrive\project\static_determine\Scene\500-5-0-0-24'
  foreach ($argument in @('-cp', $classPath, $mainClass, $scene, $Tasks, $Seed, $ReassignIterations)) {
    [void]$start.ArgumentList.Add([string]$argument)
  }
  [void]$start.ArgumentList.Add($Implementation)
  $javaProcess = [System.Diagnostics.Process]::Start($start)
  [P1ResourceJob]::VerifyMember($javaProcess.Handle)
  $stdout = $javaProcess.StandardOutput.ReadToEndAsync()
  $diagnostics = [System.Text.StringBuilder]::new()
  $ready = $false
  while ($null -ne ($line = $javaProcess.StandardError.ReadLine())) {
    if ($line -eq 'P1_COMPARISON_READY') { $ready = $true; break }
    [void]$diagnostics.AppendLine($line)
  }
  if (-not $ready) {
    $javaProcess.WaitForExit()
    throw "Comparator did not become ready: $diagnostics"
  }
  $stderr = $javaProcess.StandardError.ReadToEndAsync()
  $clock = [System.Diagnostics.Stopwatch]::StartNew()
  $javaProcess.StandardInput.BaseStream.WriteByte(1)
  $javaProcess.StandardInput.BaseStream.Flush()
  $finished = $javaProcess.WaitForExit(5000)
  if (-not $finished) {
    $javaProcess.Kill($true)
    $javaProcess.WaitForExit()
    throw 'Comparator exceeded five-second hard deadline'
  }
  $remaining = 5000 - [int]$clock.ElapsedMilliseconds
  if ($remaining -le 0 -or -not [System.Threading.Tasks.Task]::WaitAll(
      [System.Threading.Tasks.Task[]]@($stdout, $stderr), $remaining)) {
    throw 'Comparator output exceeded five-second hard deadline'
  }
  $clock.Stop()
  if ($javaProcess.ExitCode -ne 0) { throw "Comparator failed: $diagnostics $($stderr.Result)" }
  $bytes = [Text.UTF8Encoding]::new($false).GetBytes($stdout.Result)
  $stream = [IO.File]::Open($outputPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
  try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
  [Console]::Out.WriteLine("$Implementation tasks=$Tasks seed=$Seed iterations=$ReassignIterations deterministic=$Deterministic satellitePool=$SatellitePool population=12 evaluations=120 elapsedMs=$($clock.ElapsedMilliseconds) output=$outputPath")
  if ($diagnostics.Length -gt 0) { [Console]::Error.Write($diagnostics.ToString()) }
  if ($stderr.Result.Length -gt 0) { [Console]::Error.Write($stderr.Result) }
} finally {
  if ($null -ne $javaProcess -and -not $javaProcess.HasExited) {
    $javaProcess.Kill($true)
    $javaProcess.WaitForExit()
  }
  [P1ResourceJob]::Close()
  $localProcess.ProcessorAffinity = $previousAffinity
}

