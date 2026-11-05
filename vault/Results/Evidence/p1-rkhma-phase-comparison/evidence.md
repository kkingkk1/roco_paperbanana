# RKHMA重组与原版分阶段对照证据

后续进展：本证据保留下述对照发生时的接口状态。B受控试评C及A3大实例初始化的后续变更见[新证据](../p1-abc-trial-interface/evidence.md)；来源原生模块仍未直接统一到通用接口。

日期：2026-11-05。

## 问题与来源

本次核查用户提出的两个问题：初始分配后是否执行原版单星搜索，以及 `A3B3C3` 与本机来源在相同输入、参数和种子下是否产生相同结果。

- 当前项目：`E:\OneDrive\project\static_determine\roco_paperbanana`，分支 `master`。
- 来源：`E:\OneDrive\project\static_determine\satellite-basin-hopping`，分支 `main`，HEAD `fda0333454f8273d726e136d7ed67b3207754090`。工作树有用户未提交修改；只读取其已有源码和已编译类，不修改来源项目。
- 来源固定算法配置：`410000`，即DE/rand/1与PSO混合，GREEDY解码。来源原入口还支持其他配置；本次不比较它们。
- 环境：Windows，OpenJDK 21.0.4，PowerShell 7，Normal优先级。对照各进程组限制单核、2 GiB Job总提交内存和5秒端到端硬截止；重组版和来源限时入口使用4.5秒搜索截止。300任务对照使用的来源非限时入口没有内部4.5秒截止，但实际在该时间内完成。共享场景读取与样例准备另计。
- 双方均从 `E:\OneDrive\project\static_determine\Scene\500-5-0-0-24` 只读加载，在各自进程的内存中构造相同任务前缀。对任务属性、窗口和全部姿态记录逐字段生成输入SHA-256，比较前核对一致；不改写共享Scene。
- 来源运行使用其正式入口的确定性默认值，重组运行使用修正后的默认值。保留来源默认卫星线程池；单核限制仍由Windows Job入口控制。
- 代码和数据哈希、各次输入与输出身份见[清单](manifest.json)，逐次结果见[data.csv](data.csv)。

## 实际阶段

来源 `TaskAllocationCoordinator.scheduleMultiSatelliteMode` 先评价初始分配；它对每颗卫星调用 `evaluateSingleSatellite`，在初始任务池上执行 `HybCec`，用GREEDY反复解码候选顺序。初始全星方案形成后，才根据未完成任务尝试迁移、替换、交换及任务束；受影响星使用 `DeltaFastRefiner` 或再次执行单星搜索。

重组版保留上述调用路径。`A3` 设置初始归属及来源重分配，`B3` 启用来源种群搜索和局部修复，`C3` 启用来源GREEDY。两种接口的关系见下文，不将适配方式称为已统一的通用接口。

## 300任务：完整执行对照

取上述真实场景前300个任务和全部5颗卫星；共2140个窗口、344885条姿态记录。两个进程的输入指纹均为 `52ec585f808b028f2dbf6329ae5907211e4d6104f78576649ded05ed21c6e1ad`；所选窗口符合来源的1秒内部记录条件。

双方使用种群12、标称最大评价次数120、重分配最多25轮、扰动阈值1和强度0.02。来源完整优化器按整代执行，实际评价次数可能略超过标称值；原版与重组版的逐代记录一致。该小预算用于检查完整执行轨迹，不代表默认配置性能。

分别使用种子42和1234。初始阶段对照将重分配参数设为0；来源实现实际仍进入一次外循环，但没有执行重分配。两组种子均得到：

- 初始分配后，以来源首个基准个体的任务顺序做GREEDY解码：收益1550。
- 完成初始单星混合搜索后：收益1603。种子42和1234分别得到294和296条观测。
- 完成重分配后：收益1618，300条观测。

四对运行的完整输出逐行一致：初始归属、基准观测、初始优化收益、每星每次种群搜索轨迹、重分配策略与收益历史、最终归属、观测起止时间和结束状态。初始阶段每对各有10条搜索轨迹；完整运行中种子42有60条，种子1234有20条。原版结果均经[独立验证程序](VerifyComparison.java)用本项目 `ScheduleValidator` 重建并通过。

## 原版限时检查点与默认提交顺序

原版同时存在非限时原始入口和限时入口。300任务、种子42、相同固定参数下，两者均完成、收益1618，搜索和收益历史相同；限时入口只在收益严格增长时保存检查点，因此保留了较早的同收益方案，与非限时最终方案有2个任务归属和21条观测不同。重组版在完整运行完成且同收益时保留最终方案，与来源非限时入口逐项一致。两种输出均独立验证通过。

原版 `RunSingleHybridConfig` 在未显式设置 `autohyb.deterministic` 时启用确定性提交；重组版原先直接读取布尔属性，缺省为false。300任务的修正前对照中最终调度、收益和轨迹内容集合相同，但卫星轨迹记录次序不同。按卫星顺序提交是原入口默认行为，不能由这一次结果推断其余输入的最终方案必然相同。

修正前针对性测试通过延迟卫星0，让卫星1先完成：观察到轨迹次序 `[1, 1, 0, 0]`，而原入口默认应按卫星顺序为 `[0, 0, 1, 1]`。方法层将缺省值设为true后，同一测试通过；显式设为false仍保留非确定性提交。修正后的重组默认输出与原版默认输出逐行一致。

## 500任务：默认预算与截止

完整500任务、5卫星，共3586个窗口、576276条姿态记录。输入指纹均为 `374cc6b79e9879b578aae56a962d7e07935aa3b429c38a702936646e62fc5546`。种子42，双方均采用来源自动计算的种群270、标称最大评价次数27000，重分配最多25轮。

- 来源限时入口：4565毫秒，`SEARCH_EXPIRED`，0条观测，收益0。
- 重组限时入口：4652毫秒，`SEARCH_EXPIRED`，392条观测，收益2400。

双方的初始优化收益和完整搜索轨迹均不可用，说明5秒内均未完成初始全星优化；不等于没有开始单星搜索。来源限时入口只发布完成的全星检查点；重组版额外保存了来源首个基准个体的完整贪心解，故到期时可返回该基准解。这是限时返回规则差异，不是完整搜索的性能差距。两条输出均通过独立可行性和收益核验。

额外使用[来源基准程序](OriginalBaselineAudit.java)单独执行原版初始分配与GREEDY，不运行种群搜索。其收益2400、392条观测与重组截止检查点逐条一致，并经独立验证。此诊断耗时不作为完整算法运行时间。

## 接口边界

手工组合由 `TwoStageSolver` 调用 `SolverContracts.A/B/C`。来源组合由 `RkhmaRuntime.Allocation/Search/Decoder` 接入来源协调器；手工模块可经适配器参加其固定组合。`SolverContracts.B.initialPriority` 与 `proposeLocal` 当前没有接收C或受控解码回调，B不能按该通用接口自行反复评估多个C解码结果。RKHMA原生B/C通过来源协调器内部协作，因此固定组合可运行与通用接口已统一是不同结论。

此前已通过的8个手工组合和19个含来源模块的组合仍然有效。要支持任意生成的B按同一通用合同复现来源式多候选搜索，仍需调整B/C接口和运行时，再重新验收来源重组及模块互换。

自动种群另有小实例差异：来源入口按最大单星归属数乘2.5后取整，并设置最低1个；重组版在同一公式下设置最低6个。当最大单星归属数不超过2时，两者可能分别采用3或5个与6个。来源DE/rand/1自身要求至少4个个体，不能不加区分地把小种群的不同结果解释为同条件差异。本次300任务固定种群12，500任务双方自动种群均为270，均未触及该边界。

## 命令与验收

两个运行入口和共同样例构造分别见 [OriginalRkhmaAudit.java](OriginalRkhmaAudit.java)、[AssembledRkhmaAudit.java](AssembledRkhmaAudit.java) 和 [ComparisonFixture.java](ComparisonFixture.java)。使用本地来源已缓存的 Apache Commons Math 仅用于运行来源已编译类；当前项目未新增生产依赖。

```powershell
$sourceClasses = 'E:\OneDrive\project\static_determine\satellite-basin-hopping\target\classes'
$mathJar = 'C:\Users\a_wei\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar'
New-Item -ItemType Directory -Force target/p1-rkhma-phase-check/classes | Out-Null
javac --release 21 -encoding UTF-8 -cp target/classes -d target/p1-rkhma-phase-check/classes `
  'vault/Results/Evidence/p1-rkhma-phase-comparison/ComparisonFixture.java'
javac --release 21 -encoding UTF-8 -cp "target/p1-rkhma-phase-check/classes;$sourceClasses;$mathJar" `
  -d target/p1-rkhma-phase-check/classes `
  'vault/Results/Evidence/p1-rkhma-phase-comparison/OriginalRkhmaAudit.java' `
  'vault/Results/Evidence/p1-rkhma-phase-comparison/OriginalBaselineAudit.java'
javac --release 21 -encoding UTF-8 -cp 'target/p1-rkhma-phase-check/classes;target/classes' `
  -d target/p1-rkhma-phase-check/classes `
  'vault/Results/Evidence/p1-rkhma-phase-comparison/AssembledRkhmaAudit.java' `
  'vault/Results/Evidence/p1-rkhma-phase-comparison/VerifyComparison.java'

pwsh -NoProfile -File 'vault/Results/Evidence/p1-rkhma-phase-comparison/RunComparison.ps1' `
  -Implementation original -Tasks 300 -Seed 42 -ReassignIterations 0 `
  -Deterministic true -OriginalMode untimed -SatellitePool default -Budget fixed `
  -Output 'outputs/<new-original-output>.txt'
pwsh -NoProfile -File 'vault/Results/Evidence/p1-rkhma-phase-comparison/RunComparison.ps1' `
  -Implementation assembled -Tasks 300 -Seed 42 -ReassignIterations 0 `
  -Deterministic default -SatellitePool default -Budget fixed `
  -Output 'outputs/<new-assembled-output>.txt'
python -B 'vault/Results/Evidence/p1-rkhma-phase-comparison/CompareResults.py'
java -Xmx2g -cp 'target/p1-rkhma-phase-check/classes;target/classes' VerifyComparison `
  'E:\OneDrive\project\static_determine\Scene\500-5-0-0-24' 300 `
  'outputs/p1-rkhma-phase-orig-300-42-full-v2.txt'

mvn -o '-Dtest=RkhmaRuntimeTest#sourceDefaultCommitsSatelliteSearchResultsInStableOrder' test
mvn -o test
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
```

重放时须将 `<new-original-output>` 和 `<new-assembled-output>` 替换为未存在的文件名。比较脚本核对本次已保存的固定输出；它不自动读取新文件。要复核新输出，分别替换 `VerifyComparison` 命令中的规模和输出文件，并逐项比较两份输出。

针对性测试修正前失败、修正后通过。完整 `mvn -o test` 共57项通过、0失败、0错误、0跳过。README真实场景加载检查为1000任务、10卫星、2823窗口、444817条姿态记录；空调度合法、收益0。

实施中，PowerShell下将 `src/main/java/main/*.java` 作为 `rg` 路径时出现 Windows 错误123，因为该路径未展开；随后改为搜索现有目录。一次资源查询因包含未运行的 `javaw` 进程而返回非零；随后分别检查系统内存和实际Java进程。上述命令均为只读检查，没有修改来源或场景。
