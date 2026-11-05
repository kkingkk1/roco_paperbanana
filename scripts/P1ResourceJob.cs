using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

// 只约束当前运行进程组；不修改机器级配置。
public static class P1ResourceJob {
  [StructLayout(LayoutKind.Sequential)]
  private struct BasicLimits {
    public long PerProcessUserTimeLimit;
    public long PerJobUserTimeLimit;
    public uint LimitFlags;
    public UIntPtr MinimumWorkingSetSize;
    public UIntPtr MaximumWorkingSetSize;
    public uint ActiveProcessLimit;
    public UIntPtr Affinity;
    public uint PriorityClass;
    public uint SchedulingClass;
  }
  [StructLayout(LayoutKind.Sequential)]
  private struct IoCounters {
    public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
    public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
  }
  [StructLayout(LayoutKind.Sequential)]
  private struct ExtendedLimits {
    public BasicLimits BasicLimitInformation;
    public IoCounters IoInfo;
    public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
  }
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  private static extern IntPtr OpenJobObject(uint access, bool inherit, string name);
  [DllImport("kernel32.dll", SetLastError = true)]
  private static extern bool SetInformationJobObject(IntPtr job, int kind, ref ExtendedLimits info, uint length);
  [DllImport("kernel32.dll", SetLastError = true)]
  private static extern bool QueryInformationJobObject(IntPtr job, int kind, out ExtendedLimits info,
                                                        uint length, IntPtr returnedLength);
  [DllImport("kernel32.dll", SetLastError = true)]
  private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
  [DllImport("kernel32.dll", SetLastError = true)]
  private static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
  [DllImport("kernel32.dll", SetLastError = true)]
  private static extern IntPtr OpenProcess(uint access, bool inherit, int processId);
  [DllImport("kernel32.dll")]
  private static extern IntPtr GetCurrentProcess();
  [DllImport("kernel32.dll", SetLastError = true)]
  private static extern bool CloseHandle(IntPtr handle);

  private static IntPtr job;
  private const uint JobMemoryLimitFlag = 0x00000200;
  private const int ExtendedLimitInformation = 9;
  public static string Name { get; private set; }

  public static void Attach(ulong limitBytes) {
    string name = @"Local\P1Solver-" + Process.GetCurrentProcess().Id;
    Marshal.SetLastPInvokeError(0);
    job = CreateJobObject(IntPtr.Zero, name);
    int error = Marshal.GetLastPInvokeError();
    if (job == IntPtr.Zero) throw new Win32Exception(error);
    if (error == 183) {
      CloseHandle(job);
      job = IntPtr.Zero;
      throw new InvalidOperationException("Resource Job name already exists");
    }
    Name = name;
    var limits = new ExtendedLimits();
    limits.BasicLimitInformation.LimitFlags = JobMemoryLimitFlag;
    limits.JobMemoryLimit = new UIntPtr(limitBytes);
    uint length = (uint)Marshal.SizeOf(typeof(ExtendedLimits));
    if (!SetInformationJobObject(job, ExtendedLimitInformation, ref limits, length))
      throw new Win32Exception(Marshal.GetLastWin32Error());
    if (!AssignProcessToJobObject(job, GetCurrentProcess()))
      throw new Win32Exception(Marshal.GetLastWin32Error());
    if (ReadLimit(job) != limitBytes) throw new InvalidOperationException("Job memory limit was not applied");
    VerifyMember(GetCurrentProcess());
  }

  public static void VerifyMember(IntPtr process) {
    CheckMember(process, job);
  }

  // 用于从另一个进程核实实际工作进程属于指定 Job 且限制已生效。
  public static ulong InspectNamed(string name, int processId) {
    IntPtr targetJob = OpenJobObject(0x0004, false, name);
    if (targetJob == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
    IntPtr targetProcess = IntPtr.Zero;
    try {
      targetProcess = OpenProcess(0x1000, false, processId);
      if (targetProcess == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
      CheckMember(targetProcess, targetJob);
      return ReadLimit(targetJob);
    } finally {
      if (targetProcess != IntPtr.Zero) CloseHandle(targetProcess);
      CloseHandle(targetJob);
    }
  }

  private static void CheckMember(IntPtr process, IntPtr targetJob) {
    bool member;
    if (!IsProcessInJob(process, targetJob, out member)) throw new Win32Exception(Marshal.GetLastWin32Error());
    if (!member) throw new InvalidOperationException("Process did not inherit the resource Job");
  }

  private static ulong ReadLimit(IntPtr targetJob) {
    ExtendedLimits actual;
    uint length = (uint)Marshal.SizeOf(typeof(ExtendedLimits));
    if (!QueryInformationJobObject(targetJob, ExtendedLimitInformation, out actual, length, IntPtr.Zero))
      throw new Win32Exception(Marshal.GetLastWin32Error());
    if ((actual.BasicLimitInformation.LimitFlags & JobMemoryLimitFlag) == 0)
      throw new InvalidOperationException("Job memory limit flag was not applied");
    return actual.JobMemoryLimit.ToUInt64();
  }

  public static void Close() {
    if (job != IntPtr.Zero) { CloseHandle(job); job = IntPtr.Zero; }
    Name = null;
  }
}
