# P1 初始分配与受控试评证据

日期：2026-11-05。项目目录为 `E:\OneDrive\project\static_determine\roco_paperbanana`，分支 `master`，基础提交为 `8b380c5aeaa7d6b0ccd06ce9d315ad64ea53cbff`。工作树包含此前未提交工作；比较使用的是本项目实际改前副本和已记录输出，不把来源仓库的结果当作本次改前版本。

环境为 Windows、OpenJDK 21.0.4、Maven 3.9.11、PowerShell 7。相关进程为 Normal 优先级。真实场景运行按项目入口限制单核、2 GiB Job 总提交内存和公共加载后的 5 秒硬截止，搜索截止为 4.5 秒。汇总数据见 [data.csv](data.csv)，输入、代码、输出哈希及运行参数见 [manifest.json](manifest.json)。

## A3 大实例初始化

同一夹具构造 501 个任务与两颗卫星；每个任务在两星均有合法窗口，且任务之间存在时间冲突。测试使用计数解码器直接检查 A3 初始归属是否调用 C。改前调用 503 次，测试按预期失败；改后调用 0 次，并返回 501 个任务的归属。两次均执行 `mvn -o '-Dtest=A3InitializationTest' test`。

- [改前测试输出](../../../../outputs/p1-unified-abc-baseline/a3-before-test.txt)
- [改后测试输出](../../../../outputs/p1-unified-abc-baseline/a3-after-test.txt)
- [改前分配源码副本](../../../../outputs/p1-unified-abc-baseline/AssignTaskOperator.java)
- [回归测试](../../../../src/test/java/method/runtime/A3InitializationTest.java)

改前 `ProblemAccess.java` 副本根据本轮改动逐行逆向还原，其哈希与既有手工枚举证据清单一致；其余改前源码副本在修改前保存。副本和哈希用于记录边界，不覆盖原文件。

## B/C 试评与已有组合

`CandidateTrialTest` 包含 10 项检查，覆盖初始化多次试评及选取、局部编辑、整星重建、伪造候选、被 B 捕获的 C 非法输出、软硬截止、输入快照与随机流、两条运行路径、并发请求，以及 A3/C3 与试评式 B 组合。完整 `mvn -o test` 改前为 57 项通过，改后为 68 项通过；新增数量为 A3 回归 1 项和试评 10 项。[改后完整测试输出](../../../../outputs/p1-unified-abc-baseline/final-tests-v3.txt)中的运行结果为 68 项、0 失败、0 错误、0 跳过。

三个固定手工夹具中，8 个组合共 24 行的改后输出与[既有改前输出](../p1-small-enumeration/data.csv)逐字段一致。原始枚举为 103 个完整方案、其中 55 个可行；这两项是夹具规模和约束结果，不是 24 行之外新运行的组合数量。独立 oracle 检查改后输出通过。相关命令为：

```powershell
java -cp 'target/classes;target/test-classes' method.runtime.P1EnumerationAudit `
  'outputs/p1-unified-abc-baseline/manual-final.csv'
python -B 'vault/Results/Evidence/p1-small-enumeration/independent_oracle.py' `
  'outputs/p1-unified-abc-baseline/manual-final.csv'
```

`manual-final.csv` 与改前数据的 24 行各字段相同，oracle 输出 `independent_output_check=PASS rows=24`；结果见 [oracle 输出](../../../../outputs/p1-unified-abc-baseline/oracle-final.txt)。枚举证据和问题范围见[既有报告](../../Reports/p1-small-enumeration.md)。

## 300 任务来源组件轨迹回归

只读使用 `E:\OneDrive\project\static_determine\Scene\500-5-0-0-24` 的前 300 个任务和全部 5 星。比较沿用[既有对照](../p1-rkhma-phase-comparison/evidence.md)的种子 42、固定种群 12、标称最多 120 次评价、最多 25 轮重分配，以及相同默认确定性和卫星线程池配置。改后执行：

```powershell
pwsh -NoProfile -File 'vault/Results/Evidence/p1-rkhma-phase-comparison/RunComparison.ps1' `
  -Implementation assembled -Tasks 300 -Seed 42 -ReassignIterations 25 `
  -Deterministic default -SatellitePool default -Budget fixed `
  -Output 'outputs/p1-unified-abc-baseline/a3b3c3-final-300-42.txt'
```

脚本拒绝覆盖已有输出；重放时须为 `-Output` 指定 `outputs/` 内尚不存在的路径。

历史改前重组输出与历史清单的 SHA-256 一致；它与[改后输出](../../../../outputs/p1-unified-abc-baseline/a3b3c3-final-300-42.txt)的 72 行内容完全相同。两者的初始优化收益为 1603，完整运行最终收益为 1618，状态为 `COMPLETED`。既有证据已记录此输入指纹和来源非限时入口的同条件对照。此检查不覆盖超过 500 任务的初始化改动，也不证明所有输入的运行轨迹均相同。

## 1000 任务真实场景

只读加载 `E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12`，1000 任务、10 星、2823 窗口和 444817 条姿态记录。README 场景检查的空方案可行、收益为 0。实际求解使用 A3B3C3、种子 42。本次复用了已编译的独立核验器；以下 `javac` 命令用于重放时准备核验器：

```powershell
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
pwsh -NoProfile -File scripts/run-manual.ps1 `
  -Scene 'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' `
  -Tasks 1000 -Satellites 10 -Combination A3B3C3 -Seed 42
javac --release 21 -encoding UTF-8 -cp target/classes -d target/p1-task2-audit `
  'vault/Results/Evidence/p1-two-stage-runtime/P1SolverCheck.java'
java -Xmx2g -cp 'target/classes;target/p1-task2-audit' audit.P1SolverCheck `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10 `
  'outputs/p1-unified-abc-baseline/a3b3c3-final-1000.txt'
```

[运行输出](../../../../outputs/p1-unified-abc-baseline/a3b3c3-final-1000.txt)记录 `status=SUCCESS`、`profit=1972`、`observations=285`、`elapsedMs=4660.826` 和 `detail=SEARCH_EXPIRED`。既有独立核验器重新定位原始姿态记录，[输出](../../../../outputs/p1-unified-abc-baseline/verify-final-1000.txt)为 `feasible=true profit=1972 observations=285`。这是截止时保留的可行检查点，不代表完整优化结束；本轮未在该输入上执行同预算改前收益对照。

## 范围与剩余工作

接口允许 B 在单次调用内通过运行时反复请求 C，只有本次会话实际签发且校验通过的候选可被选择。原生来源模块仍经 `RkhmaRuntime.Allocation/Search/Decoder` 和来源协调器运行，尚未直接实现 `SolverContracts.A/B/C`；固定组合和通用 B 的适配试评通过，不代表此接口统一已经完成。小实例自动种群下限差异也仍待核实。P1 阶段因此保持进行中。

## 文档检查记录

临时 Markdown 链接检查脚本通过 `python -` 执行。首次输出因 Windows 终端默认 GBK 编码无法打印标题中的 `☑` 而失败；在本进程设置 `PYTHONIOENCODING=utf-8` 后可打印诊断。随后发现脚本误把标题中的状态符号保留在链接锚点，造成两项误报；修正脚本以排除标点和符号后重跑，13 个本轮相关 Markdown 文件的 193 个本地链接及公式美元符配对检查通过。此脚本仅用于本次验收。
