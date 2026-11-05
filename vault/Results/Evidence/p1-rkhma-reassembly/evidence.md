# P1完整RKHMA拆分与重组证据

日期：2026-11-05。

## 范围与环境

- 工作目录：`E:\OneDrive\project\static_determine\roco_paperbanana`。
- 来源：`E:\OneDrive\project\static_determine\satellite-basin-hopping`，`main` 分支，HEAD `fda0333454f8273d726e136d7ed67b3207754090`。来源工作树有未提交修改，本次只读取；逐文件来源和迁入哈希见[清单](manifest.json)。
- 合同：[问题建模](../../../Knowledge/问题建模.md)和[方法及评价合同](../../../Knowledge/多智能体算法与评价合同.md)。
- 环境：Windows，OpenJDK 21.0.4，Maven 3.9.11，PowerShell 7；进程保持 Normal 优先级。
- 真实场景入口使用单核、2 GiB Job总提交内存、搜索截止4.5秒及端到端5秒硬截止。公共数据读取另计。
- 原始观测汇总于[data.csv](data.csv)；各条仍使用原始窗口和姿态时间。输入、输出及代码哈希记录在[清单](manifest.json)。

## 自动测试与来源对照

```powershell
mvn -o test
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
```

55项测试通过，0失败、0错误、0跳过。测试保留原有8个手工组合，检查19个包含来源模块的组合，以及初始化、重分配、本星整体重建、非1秒姿态记录、连续移动后的当前时间轴、非法C输出和软硬截止。超过4个额外任务的测试在一次移动中调整5个已有任务，所得调度合法并被接受。

真实场景加载检查得到1000个任务、10颗卫星、2823个窗口和444817条姿态记录；空调度合法、收益为0。

[SourceComparator.java](SourceComparator.java) 使用来源项目已编译类执行固定 `410000`。其依赖来源本机已缓存的 Apache Commons Math，仅用于运行来源对照，不是本项目生产依赖。来源种子1234、种群6、最大评价次数30、重分配最多3轮、扰动阈值2、强度0.1；本项目测试使用相同条件。

```powershell
$sourceProject = 'E:\OneDrive\project\static_determine\satellite-basin-hopping'
$mathJar = 'C:\Users\a_wei\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar'
New-Item -ItemType Directory -Force -Path target/rkhma-source-check/classes | Out-Null
javac --release 21 -encoding UTF-8 -cp "$sourceProject\target\classes;$mathJar" `
  -d target/rkhma-source-check/classes `
  'vault/Results/Evidence/p1-rkhma-reassembly/SourceComparator.java'
java -cp "target/rkhma-source-check/classes;$sourceProject\target\classes;$mathJar" SourceComparator 6
java -cp "target/rkhma-source-check/classes;$sourceProject\target\classes;$mathJar" SourceComparator 501
java -cp "target/rkhma-source-check/classes;$sourceProject\target\classes;$mathJar" SourceComparator 6 force
```

三个夹具的来源输出与本项目测试逐项一致：

- 6任务与501任务分别使用来源的两条初始分配路径。两者均有6个带窗口任务；501任务夹具中的其余495个任务无窗口。两者最终收益均为32，重分配历史为 `HoldInitialAssignment, ReassignRejected, ReassignRejected`。
- `6 force` 把初始6个任务都分配到卫星0，触发并接受迁移。最终收益32，重分配历史为 `HoldInitialAssignment, MaxWindowLength, ReassignRejected`，最终归属为 `{0=1, 1=1, 2=0, 3=1, 4=1, 5=0}`。
- 两种常规夹具的最终归属为 `{0=1, 1=0, 2=1, 3=0, 4=1, 5=0}`。测试同时逐项比较初始归属、最终归属、历史、任务和卫星及观测起止时间。

以上只证明这些受控夹具上的来源一致性。非1秒内部记录改用实际时间和合法候选适配，不声称逐步复制来源的窗口偏好或搜索轨迹。

## 真实场景烟测

场景为 `E:\OneDrive\project\static_determine\Scene\500-5-0-0-24`，500个任务、5颗卫星，种子42。下例中的组合依次取 `A3B3C3`、`A3B1C3`、`A3B3C1`、`A1B3C3`：

```powershell
pwsh -NoProfile -File scripts/run-manual.ps1 `
  -Scene 'E:\OneDrive\project\static_determine\Scene\500-5-0-0-24' `
  -Tasks 500 -Satellites 5 -Combination A3B3C3 -Seed 42

New-Item -ItemType Directory -Force -Path target/p1-rkhma-audit | Out-Null
javac -encoding UTF-8 -cp target/classes -d target/p1-rkhma-audit `
  'vault/Results/Evidence/p1-two-stage-runtime/P1SolverCheck.java'
java -Xmx2g -cp 'target/classes;target/p1-rkhma-audit' audit.P1SolverCheck `
  'E:\OneDrive\project\static_determine\Scene\500-5-0-0-24' 500 5 `
  'outputs/p1-rkhma-real-final-20261105.txt'
```

每次输出都用上述独立复核器重建并通过完整问题验证；其余三次将最后一个参数换为对应输出文件：

- `A3B3C3`：收益2400，392条观测，4768.461毫秒，[原始输出](../../../../outputs/p1-rkhma-real-final-20261105.txt)。
- `A3B1C3`：收益2458，394条观测，4643.632毫秒，[原始输出](../../../../outputs/p1-rkhma-mixed-b1-final-20261105.txt)。
- `A3B3C1`：收益2400，392条观测，4650.927毫秒，[原始输出](../../../../outputs/p1-rkhma-mixed-c1-final-20261105.txt)。
- `A1B3C3`：收益2110，387条观测，4685.957毫秒，[原始输出](../../../../outputs/p1-rkhma-mixed-a1-20261105.txt)。

四次均为 `SUCCESS`、`SEARCH_EXPIRED`。搜索截止时返回此前保存的可行方案；这些是单场景单种子的工程检查，不是性能比较，也不说明完整搜索已经结束。

## 实施中的失败与修正

- 初次来源组合到期时返回空解，因为来源完整逐星搜索尚未结束。限时入口增加来源首个基准种群个体的可行检查点；受控时钟测试及真实场景复核通过。原始记录为 `outputs/p1-rkhma-real-20261105.txt`。
- 早期手工B与来源A组合到期时返回空解，因为首星局部搜索占用了全部预算。手工B按合同使用逐星初始预算和每次受影响星预算，保留来源B原有截止方式。修正后烟测与独立复核通过。原始记录为 `outputs/p1-rkhma-mixed-b1-20261105.txt`。
- 早期自定义C组合未把结果写回来源调度对象。适配层改为写回可变调度列表，阶段调用测试与真实场景复核通过。
- 初次来源夹具未在开始搜索前将初始任务归属装入卫星对象，与来源正式入口顺序不一致。对照程序及适配均按来源顺序装载后，重新执行三个夹具和真实场景检查。
