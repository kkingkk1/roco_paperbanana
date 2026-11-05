# P1 独立阶段策略证据

日期：2026-11-05。项目目录 `E:\OneDrive\project\static_determine\roco_paperbanana`，分支 `master`，基础提交 `8b380c5aeaa7d6b0ccd06ce9d315ad64ea53cbff`；工作树含此前未提交工作。环境为 Windows、OpenJDK 21.0.4、Maven 3.9.11、PowerShell 7，进程 Normal 优先级。真实场景求解受单核、2 GiB Job 总提交内存和公共数据加载后 5 秒硬截止限制，搜索截止为 4.5 秒。

## 改动与接口

- A1 初始按合法候选数量和负载分配；重分配优先遍历高收益未完成任务。A2 初始按任务可选卫星数量和归一化负载分配；重分配优先遍历可转移卫星少的未完成任务和低占用卫星。两者各自实现转移与交换。
- B1 初始按收益密度排序，B2 初始结合收益密度和候选稀缺度排序。初始化局部改进和重分配局部调整有各自的 B 入口；B1 与 B2 的局部策略分别实现。通用候选访问、试评门禁和验证器不属于策略实现。
- 移除旧的 `searchLocal` / `proposeLocal`。新入口为 `searchInitialLocal` / `proposeInitialLocal` 和 `searchReassignment` / `proposeReassignmentLocal`。自定义 B 须分别实现两个阶段，或直接分别覆写两个试评入口。旧接口没有保留兼容转发。

改前源码、相关测试与 24 行手工组合结果保留在 [outputs/p1-independent-policies](../../../../outputs/p1-independent-policies/)，路径和哈希见[改前清单](../../../../outputs/p1-independent-policies/baseline.json)。可与上一轮证据比较的源码及 CSV 已核对历史哈希；该项目自身的改前结果是本次 comparator。

## 直接决策检查

`IndependentPoliciesTest` 的同一三星夹具中：

- 初始任务 0：A1 归属到卫星 1，A2 归属到卫星 0。
- 同一已有调度的首次重分配：A1 将任务 0 转至卫星 1，A2 转至卫星 2。

另一单星夹具中，B1 的初始顺序为 `[0, 1]`，B2 为 `[1, 0]`。初始化局部改进和两个任务均转入时的第一个重分配局部候选中，B1 均选择任务 0，B2 均选择任务 1。仅任务 1 转入时，B1 的重分配候选转为任务 1；仅任务 0 转入时，B2 的重分配候选转为任务 0。既有两条运行路径测试分别断言初始化入口与重分配入口收到正确阶段，并覆盖多次试评、越权、软硬截止及提交或撤销。

```powershell
mvn -o '-Dtest=IndependentPoliciesTest' test
mvn -o test
```

最终[完整测试输出](../../../../outputs/p1-independent-policies/full-tests-final-v2.txt)为 70 项、0 失败、0 错误、0 跳过。

## 手工组合改前对照

沿用固定时钟、种子 42 的三个手工枚举夹具。103 个完整方案中 55 个可行；8 个手工组合共 24 行改后数据与[改前数据](../../../../outputs/p1-independent-policies/before-manual.csv)逐字段一致。新数据保存为本证据包的 [data.csv](data.csv)。独立 oracle 检查 24 行通过。

```powershell
java -cp 'target/classes;target/test-classes' method.runtime.P1EnumerationAudit 'outputs/<新文件>.csv'
python -B 'vault/Results/Evidence/p1-small-enumeration/independent_oracle.py' 'outputs/<新文件>.csv'
```

枚举程序要求输出文件不存在。此对照仅覆盖三个小夹具；输出相同不表示策略在其他输入上没有差异。

## RKHMA 来源组件回归

沿用[既有 300 任务对照](../p1-rkhma-phase-comparison/evidence.md)：场景 `E:\OneDrive\project\static_determine\Scene\500-5-0-0-24` 的前 300 个任务与 5 星，种子 42、固定种群 12、标称最多 120 次评价、最多 25 轮重分配。

```powershell
pwsh -NoProfile -File 'vault/Results/Evidence/p1-rkhma-phase-comparison/RunComparison.ps1' `
  -Implementation assembled -Tasks 300 -Seed 42 -ReassignIterations 25 `
  -Deterministic default -SatellitePool default -Budget fixed `
  -Output 'outputs/p1-independent-policies/rkhma-after-300-42.txt'
```

脚本要求输出路径不存在；重放时须换用 `outputs/` 内的新路径。[改后输出](../../../../outputs/p1-independent-policies/rkhma-after-300-42.txt)与[改前输出](../../../../outputs/p1-unified-abc-baseline/a3b3c3-final-300-42.txt)逐行一致，共 72 行，最终收益 1618，状态 `COMPLETED`。此输入未触及 A3 超过 500 任务的分支。

## 真实场景检查

只读加载 `E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12`。README 检查得到 1000 任务、10 星、2823 窗口、444817 条姿态记录，空方案可行且收益为 0。其后以 A2B2C2 和种子 42 求解：

```powershell
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
pwsh -NoProfile -File scripts/run-manual.ps1 `
  -Scene 'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' `
  -Tasks 1000 -Satellites 10 -Combination A2B2C2 -Seed 42
java -Xmx2g -cp 'target/classes;target/p1-task2-audit' audit.P1SolverCheck `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10 `
  'outputs/p1-independent-policies/real-a2b2c2.txt'
```

独立核验器复用此前已编译类，其源码为 [P1SolverCheck.java](../p1-two-stage-runtime/P1SolverCheck.java)；重放时可按[先前证据](../p1-two-stage-runtime/evidence.md)编译。实际[求解输出](../../../../outputs/p1-independent-policies/real-a2b2c2.txt)为 `status=SUCCESS profit=1870 observations=260 elapsedMs=4629.461 detail=SEARCH_EXPIRED`；[独立核验](../../../../outputs/p1-independent-policies/real-check.txt)为 `feasible=true profit=1870 observations=260`。本次没有该输入、同组合、同预算的改前收益比较。

## 限制

以上是真实调用与固定夹具的工程验收，不表示新策略优于旧策略。RKHMA 原生模块仍通过来源协调器及专用接口运行，尚未直接实现通用 A/B/C；小实例自动种群下限差异仍待核实。P1 继续进行。输入、源码和产物哈希见[清单](manifest.json)。
