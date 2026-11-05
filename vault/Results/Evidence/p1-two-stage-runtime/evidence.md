# P1两阶段运行时证据

日期：2026-11-04。

## 范围与环境

本记录验收P1任务2：A/B/C两阶段接口、8个手工组合、受影响星副本编辑、严格收益接受、权限与截止处理。
用户确认借鉴RKHMA的分配、局部搜索和插入思路，同时保留既定8组合；来源与差异见[来源记录](../../../Knowledge/Source-Provenance.md#rkhma-方法借鉴)。
本记录不是完整RKHMA复现或方法性能比较，也不代替任务3的多实例枚举验收。

- 工作目录：`E:\OneDrive\project\static_determine\roco_paperbanana`。
- 合同：[问题建模](../../../Knowledge/问题建模.md)与[方法及评价合同](../../../Knowledge/多智能体算法与评价合同.md)。
- 环境：Windows，Java 21.0.4，Maven 3.9.11，PowerShell 7；执行进程保持 Normal 优先级。
- 运行代码尚未提交；基准 Git HEAD、实际源码哈希、输入文件哈希与参数见 [manifest.json](manifest.json)。
- 公共场景读取不计入实例时间；代码预处理、搜索、完整验证和结果写入控制进程的输出流均计入。

## 自动测试

```powershell
mvn -o test
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
```

完整测试46项通过，0失败、0错误、0跳过：既有25项问题层测试，加14项两阶段运行时测试和7项进程运行测试。
8个组合均在可独立枚举的三任务实例上运行，完成两个阶段的B/C调用，通过完整验证，收益不超过枚举最优值14。
其他测试覆盖转移失败后继续搜索、两任务归属交换、严格收益接受、无效新相邻边回滚、C越权、陈旧版本、跨多次提案累计的4任务范围、策略跨次运行状态、实例随机流、软截止和硬超时。

受控子进程忽略中断时，外层硬截止测试确认进程被终止。
Windows Job 测试为夹具设置512 MiB上限，尝试启动初始堆768 MiB的子JVM；子进程以退出码1失败，证明该测试的总提交内存限制生效。
正式入口固定2 GiB；夹具限额仅用于测试，不改变正式预算。

## 真实场景烟测

使用共享 `Scene\500-5-0-0-24`，500个任务、5颗卫星，组合 `A1B1C1`，求解种子42。
执行入口及独立复核命令：

```powershell
pwsh -NoProfile -File scripts/run-manual.ps1 `
  -Scene 'E:\OneDrive\project\static_determine\Scene\500-5-0-0-24' `
  -Tasks 500 -Satellites 5 -Combination A1B1C1 -Seed 42

javac -encoding UTF-8 -cp target/classes -d target/p1-task2-audit `
  'vault/Results/Evidence/p1-two-stage-runtime/P1SolverCheck.java'
java -Xmx2g -cp 'target/classes;target/p1-task2-audit' audit.P1SolverCheck `
  'E:\OneDrive\project\static_determine\Scene\500-5-0-0-24' 500 5 `
  'vault/Results/Evidence/p1-two-stage-runtime/data.csv' 2381
```

实际入口由 `Start-Process` 隐藏窗口启动，标准输出和错误分别保存于 `outputs/p1-task2-final3.stdout.txt` 与 `outputs/p1-task2-final3.stderr.txt`。
退出码为0，标准错误为空；输出状态为 `SUCCESS`，截止原因为 `SEARCH_EXPIRED`，收益2381，观测389条，端到端计时4673.905毫秒。
该计时从公共数据读取结束、控制脚本发送启动信号开始，至Java控制进程退出且结果流完整接收为止；后续控制脚本向调用方转发结果不计入。

运行中读取到工作进程 `22932` 的优先级为 Normal、CPU亲和性掩码为1。
通过 `P1ResourceJob.InspectNamed` 确认该进程属于本次 Job，Job 总提交内存上限为2147483648字节；运行后该进程已退出。
该数值是操作系统设置的限制，不是采样到的峰值内存。

[data.csv](data.csv)保存输出的389条实际观测。
[P1SolverCheck.java](P1SolverCheck.java)重新读取共享场景，按窗口序号和精确时刻定位原始姿态对象；独立 `ScheduleValidator` 返回 `feasible=true`、收益2381、观测389条。
CSV与最终烟测输出逐行一致。

## 失败与修正记录

- 同一策略对象跨次复用时，旧移动游标残留。针对性测试先复现 `expected: <3> but was: <0>`；在初始分配时重置游标后，同一测试通过。
- 在2 GiB Job内首次启动时子JVM未完成协议。诊断发现控制器与工作JVM的默认初始堆各约1.071 GB，合计超过进程组限制。仅将控制器初始堆设为64 MiB；工作JVM和Job的2 GiB上限不变，小夹具及真实场景随后通过。
- 加入端到端硬截止后的真实场景曾返回 `HARD_TIMEOUT`；具体耗时归因未完全确定。另一次诊断运行中，求解结果传回约4623毫秒，逐行输出约61毫秒。随后改为批量输出，并继续使用4.5秒搜索截止和5秒硬上限；最终烟测在4673.905毫秒完成。
- 命令行原先将时刻格式化到6位小数，可能不能恢复原始姿态记录。现输出可往返的 `double` 字符串，并用9位小数夹具验证。

## 限制

真实烟测只覆盖一个场景和一个组合；固定墙钟截止使同一种子不同运行的搜索进度可能不同。
脚本依赖Windows、PowerShell 7与系统Job对象；直接Java接口不设置操作系统级CPU及内存限制。
本记录时任务3仍需多个独立可枚举实例及对应验收证据；后续结果见[手工实例枚举报告](../../Reports/p1-small-enumeration.md)。
