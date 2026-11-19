param(
  [Parameter(Mandatory = $true)][string]$Scene,
  [Parameter(Mandatory = $true)][ValidateRange(0, 2147483647)][int]$Tasks,
  [Parameter(Mandatory = $true)][ValidateRange(1, 2147483647)][int]$Satellites,
  [Parameter(Mandatory = $true)][ValidatePattern('^A[123]B[123]C[123]$')][string]$Combination,
  [Parameter(Mandatory = $true)][long]$Seed
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw '当前资源限制入口需要 Windows 和 PowerShell 7' }
Add-Type -Path (Join-Path $PSScriptRoot 'P1ResourceJob.cs')

$projectRoot = Split-Path -Parent $PSScriptRoot
$localProcess = Get-Process -Id $PID
$previousAffinity = $localProcess.ProcessorAffinity
$availableMask = $previousAffinity.ToInt64()
$chosenMask = [long]1
while (($availableMask -band $chosenMask) -eq 0) {
  $chosenMask = $chosenMask -shl 1
  if ($chosenMask -eq 0) { throw '当前进程没有可用的 CPU 亲和性掩码' }
}

$solverProcess = $null
Push-Location -LiteralPath $projectRoot
try {
  $localProcess.ProcessorAffinity = [IntPtr]$chosenMask
  if ($localProcess.ProcessorAffinity.ToInt64() -ne $chosenMask) { throw '单核亲和性设置失败' }
  [P1ResourceJob]::Attach([UInt64]2147483648)
  $start = [System.Diagnostics.ProcessStartInfo]::new()
  $start.FileName = (Get-Command python -CommandType Application | Select-Object -First 1).Source
  $start.WorkingDirectory = $projectRoot
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardInput = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $start.Environment['P1_RESOURCE_JOB_NAME'] = [P1ResourceJob]::Name
  $start.Environment['PYTHONPATH'] = Join-Path $projectRoot 'src/main/python'
  foreach ($argument in @('-B', '-m', 'satellite_scheduler.manual_supervisor', $Scene,
      $Tasks, $Satellites, $Combination, $Seed)) {
    [void]$start.ArgumentList.Add([string]$argument)
  }
  $solverProcess = [System.Diagnostics.Process]::Start($start)
  [P1ResourceJob]::VerifyMember($solverProcess.Handle)
  if ($solverProcess.PriorityClass -ne [System.Diagnostics.ProcessPriorityClass]::Normal) {
    throw '验证进程不是 Normal 优先级'
  }
  $stdout = $solverProcess.StandardOutput.ReadToEndAsync()
  $diagnostics = [System.Text.StringBuilder]::new()
  $ready = $false
  while ($null -ne ($line = $solverProcess.StandardError.ReadLine())) {
    if ($line -match '^P1_INSTANCE_READY:([0-9]+)$') {
      $workerId = [int]$Matches[1]
      if ($workerId -eq $solverProcess.Id) { throw '求解与验证必须使用不同进程' }
      if ([P1ResourceJob]::InspectNamed([P1ResourceJob]::Name, $workerId) -ne 2147483648) {
        throw '求解进程的 Job 内存限制不正确'
      }
      $workerProcess = Get-Process -Id $workerId
      if ($workerProcess.ProcessorAffinity.ToInt64() -ne $chosenMask) {
        throw '求解进程的单核亲和性不正确'
      }
      if ($workerProcess.PriorityClass -ne [System.Diagnostics.ProcessPriorityClass]::Normal) {
        throw '求解进程不是 Normal 优先级'
      }
      [Console]::Error.WriteLine("P1_PROCESSES validation=$($solverProcess.Id) solver=$workerId jobMemoryBytes=2147483648 affinity=$chosenMask priority=Normal")
      $ready = $true
      break
    }
    [void]$diagnostics.AppendLine($line)
  }
  if (-not $ready) {
    $solverProcess.WaitForExit()
    [Console]::Out.Write($stdout.GetAwaiter().GetResult())
    [Console]::Error.Write($diagnostics.ToString())
    if ($solverProcess.ExitCode -eq 0) { throw '求解进程未发送计时启动信号' }
    exit $solverProcess.ExitCode
  }
  $stderr = $solverProcess.StandardError.ReadToEndAsync()
  $wallClock = [System.Diagnostics.Stopwatch]::StartNew()
  $solverProcess.StandardInput.BaseStream.WriteByte(1)
  $solverProcess.StandardInput.BaseStream.Flush()
  $finished = $solverProcess.WaitForExit(5000)
  $streamsDone = $false
  if ($finished) {
    $remainingMilliseconds = 5000 - [int]$wallClock.ElapsedMilliseconds
    if ($remainingMilliseconds -gt 0) {
      $streamsDone = [System.Threading.Tasks.Task]::WaitAll(
        [System.Threading.Tasks.Task[]]@($stdout, $stderr), $remainingMilliseconds)
    }
  }
  $wallClock.Stop()
  $elapsedText = $wallClock.Elapsed.TotalMilliseconds.ToString('F3', [Globalization.CultureInfo]::InvariantCulture)
  if (-not $finished -or -not $streamsDone -or $wallClock.Elapsed.TotalMilliseconds -ge 5000) {
    if (-not $solverProcess.HasExited) { $solverProcess.Kill($true) }
    $solverProcess.WaitForExit()
    [Console]::Error.Write($diagnostics.ToString())
    if ($stderr.IsCompletedSuccessfully) { [Console]::Error.Write($stderr.Result) }
    [Console]::Out.WriteLine("status=HARD_TIMEOUT profit=null observations=0 elapsedMs=$elapsedText detail=end_to_end_deadline")
    exit 1
  }
  $outputText = $stdout.Result
  $elapsedMarker = ' elapsedMs='
  $elapsedStart = $outputText.IndexOf($elapsedMarker, [StringComparison]::Ordinal)
  if ($elapsedStart -ge 0) {
    $elapsedStart += $elapsedMarker.Length
    $elapsedEnd = $outputText.IndexOf(' detail=', $elapsedStart, [StringComparison]::Ordinal)
    if ($elapsedEnd -gt $elapsedStart) {
      $outputText = $outputText.Substring(0, $elapsedStart) + $elapsedText + $outputText.Substring($elapsedEnd)
    }
  }
  [Console]::Out.Write($outputText)
  [Console]::Error.Write($diagnostics.ToString())
  [Console]::Error.Write($stderr.Result)
  if ($solverProcess.ExitCode -ne 0) { exit $solverProcess.ExitCode }
} finally {
  if ($null -ne $solverProcess -and -not $solverProcess.HasExited) {
    $solverProcess.Kill($true)
    $solverProcess.WaitForExit()
  }
  [P1ResourceJob]::Close()
  $localProcess.ProcessorAffinity = $previousAffinity
  Pop-Location
}
