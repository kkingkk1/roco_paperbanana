param(
  [Parameter(Mandatory = $true)][string]$Scene,
  [Parameter(Mandatory = $true)][ValidateRange(0, 2147483647)][int]$Tasks,
  [Parameter(Mandatory = $true)][ValidateRange(1, 2147483647)][int]$Satellites,
  [Parameter(Mandatory = $true)][ValidatePattern('^A[123]B[123]C[123]$')][string]$Combination,
  [Parameter(Mandatory = $true)][long]$Seed
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw '当前资源限制入口需要 Windows 和 PowerShell 7' }

# 内层脚本由独立 PowerShell 进程执行，避免把用户当前交互式 shell 加入 Job。
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

$javaProcess = $null
Push-Location -LiteralPath $projectRoot
try {
  $localProcess.ProcessorAffinity = [IntPtr]$chosenMask
  if ($localProcess.ProcessorAffinity.ToInt64() -ne $chosenMask) { throw '单核亲和性设置失败' }
  [P1ResourceJob]::Attach([UInt64]2147483648)
  $start = [System.Diagnostics.ProcessStartInfo]::new()
  $start.FileName = (Get-Command java -CommandType Application).Source
  $start.WorkingDirectory = $projectRoot
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardInput = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $start.Environment['P1_RESOURCE_JOB_NAME'] = [P1ResourceJob]::Name
  foreach ($argument in @('-Xms64m', '-Xmx2g', '-XX:ActiveProcessorCount=1', '-cp', 'target/classes',
      'main.RunManualSolver', $Scene, $Tasks, $Satellites, $Combination, $Seed)) {
    [void]$start.ArgumentList.Add([string]$argument)
  }
  $javaProcess = [System.Diagnostics.Process]::Start($start)
  [P1ResourceJob]::VerifyMember($javaProcess.Handle)
  $stdout = $javaProcess.StandardOutput.ReadToEndAsync()
  $diagnostics = [System.Text.StringBuilder]::new()
  $ready = $false
  while ($null -ne ($line = $javaProcess.StandardError.ReadLine())) {
    if ($line -eq 'P1_INSTANCE_READY') { $ready = $true; break }
    [void]$diagnostics.AppendLine($line)
  }
  if (-not $ready) {
    $javaProcess.WaitForExit()
    [Console]::Out.Write($stdout.GetAwaiter().GetResult())
    [Console]::Error.Write($diagnostics.ToString())
    if ($javaProcess.ExitCode -eq 0) { throw '求解进程未发送计时启动信号' }
    exit $javaProcess.ExitCode
  }
  $stderr = $javaProcess.StandardError.ReadToEndAsync()
  $wallClock = [System.Diagnostics.Stopwatch]::StartNew()
  $javaProcess.StandardInput.BaseStream.WriteByte(1)
  $javaProcess.StandardInput.BaseStream.Flush()
  $finished = $javaProcess.WaitForExit(5000)
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
    if (-not $javaProcess.HasExited) { $javaProcess.Kill($true) }
    $javaProcess.WaitForExit()
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
  if ($javaProcess.ExitCode -ne 0) { exit $javaProcess.ExitCode }
} finally {
  if ($null -ne $javaProcess -and -not $javaProcess.HasExited) {
    $javaProcess.Kill($true)
    $javaProcess.WaitForExit()
  }
  [P1ResourceJob]::Close()
  $localProcess.ProcessorAffinity = $previousAffinity
  Pop-Location
}
