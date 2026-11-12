# RoCo 与 PaperBanana 多智能体调度算法研发

本项目面向已确定的多卫星成像调度问题，结合RoCo与PaperBanana的多智能体思想，演化完整A/B/C调度算法。
在统一求解条件下，评价其相对手工起点的收益与实际成本。
智能体在研发阶段检索方法、规划设计、生成代码、分析执行结果并协作改进，最终运行生成并冻结的求解器。

当前已实现问题输入、候选迭代、完整调度验证、两阶段手工运行时、可互换的RKHMA A/B/C模块，以及案例读取与完整性校验。
P1工程验收已完成；P2的五角色协作演化系统尚未实现。

## 研究路线

离线演化采用Retriever、A任务分配、B单星优先级与局部搜索、C调度解码和Critic五类角色。
每轮A/B/C基于同一完整父版本修改各自模块，程序统一评价全部8种新旧搭配，Critic统一分析，程序选择下一轮父版本并在代末取Top N。
Retriever每次独立演化只初始化检索一次；A/B/C分别保留反思与长期记忆，协同经验另存。

冻结代码求解实例时，统一目标流程为A初始归属、B/C单星搜索，再基于已有调度由A重分配并由B/C更新。每个A/B/C版本都支持两个阶段，实例内不调用LLM。
后台采用候选记录、评价记录和运行级公共信息；公共信息每次运行只存一份。正式案例按决策逻辑去重，元数据和完整算法源码存于独立的[案例目录](cases/README.md)；入库时不要求已适配本项目接口或预先证明收益更高，案例`source`仅留后台。

阶段状态与路线见[主计划P0—P4](vault/Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)，接口、先导配置及评价规则见[方法与评价合同](vault/Knowledge/多智能体算法与评价合同.md)。
已完成的起点验收见[P1：可运行求解器起点](vault/Plan/mechanisms/P1-可运行求解器起点.md)；后续协作演化工作见[P2阶段计划](vault/Plan/mechanisms/P2-五角色协作演化.md)。
[方法参照](vault/Knowledge/算法设计方法参照.md)与[演化示例](vault/Writing/演化机制示例与汇报提纲.md)供理解机制，不替代已确认合同。

## 环境与验证

使用 Java 21、Maven。生产代码仅依赖 Java 标准库。
测试沿用来源项目的 JUnit 5.10.0。

```powershell
mvn -o test
```

`-o` 使用本机已有 Maven 缓存。本次验证没有安装或下载依赖。
新机器缺少缓存时，需先准备 `pom.xml` 声明的构建插件和测试依赖。

## 读取场景

数据沿用原格式。显式传入场景目录、任务数和卫星数。
共享 `Scene` 保留在原位置，不复制、不改写。
加载器保留每个窗口的全部原始姿态记录。
问题模型的完整观测候选域由排除原始首尾后的内部等间隔序列定义，只使用索引1至N−2。
首尾附加记录不参与内部等距检查。观测引用原始姿态对象，不重采样、不改变步长、不插值补齐记录。
构造 `ProblemInstance` 时，内部间隔不等会被判为输入错误。

```powershell
java -Xmx2g -cp target/classes main.InspectScene `
  'F:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' `
  1000 10
```

该入口只报告数据规模和空方案的合法性，不生成调度。

## 运行调度组合

先执行离线测试，再使用 Windows PowerShell 7 入口运行单个组合：

```powershell
.\scripts\run-manual.ps1 `
  -Scene 'F:\OneDrive\project\static_determine\Scene\500-5-0-0-24' `
  -Tasks 500 -Satellites 5 -Combination A1B1C1 -Seed 42
```

原有 `A1/A2`、`B1/B2`、`C1/C2` 保持可用，形成8个纯手工组合。
新增 `A3`、`B3`、`C3`，分别提供RKHMA的分配与重分配、单星混合搜索与局部修补、贪心解码。
`A3B3C3`按来源固定配置 `410000` 组装，已在记录的兼容输入上完成来源对照；各模块实现共同的 `SolverContracts.A/B/C` 接口，可以替换，例如 `A3B1C3`。
三类模块各有三种选择，共27种组合；其中19种包含至少一个RKHMA模块。
纯手工组合沿用原运行时；包含RKHMA模块的组合使用 `RkhmaRuntime`，其来源校准可能重新调整未受影响星。
通用A和B都可受控地比较多个候选。B使用`searchInitial`和`searchInitialLocal`完成初始单星搜索，使用独立的`searchReassignment`处理重分配。来源需要全星校准时，B另通过`searchCalibration`处理重分配后的整星搜索。具体职责与校验见[方法合同](vault/Knowledge/多智能体算法与评价合同.md#11-两阶段接口与职责)。
A3对所有任务规模使用来源不超过500任务时的复合初始分配规则，即`initializeWithStrategy(..., 7)`。初始分配后不再执行A的跨星局部修补；B的初始单星搜索保留。
手工B保留初始化的独立局部改进；B3执行来源的初始混合搜索，不额外追加来源中没有的独立初始化局部步骤。
所有规模沿用来源不超过500任务时的搜索与校准方式。B3默认对有任务且无继承状态的卫星执行两次初始种群搜索；不再启用来源针对4000及以上任务的搜索缩减与延迟校准。
输出首行给出状态、收益、观测数与计时；后续每行给出任务、卫星、原始窗口序号和精确时刻。
`SEARCH_EXPIRED` 表示搜索截止后返回此前可行调度；`HARD_TIMEOUT` 或 `FAILURE` 表示失败，没有有效收益。
RKHMA在搜索截止时可能只返回来源首个基准种群个体的调度，不能把该结果称为完整搜索的终点。

该入口为独立进程组设置单核亲和性和 2 GiB Windows Job 总提交内存上限。
公共数据读取完成后，实例搜索截止为 4.5 秒，包含结果输出的硬上限为 5 秒；进程超时会被终止。
直接调用 Java 的 `TwoStageSolver`、`RkhmaRuntime` 或 `ForkedSolver` 不会自动设置操作系统级 CPU 和内存限制，正式按该合同运行应使用上述脚本。
RKHMA来源、非1秒记录适配与已验证范围见[来源记录](vault/Knowledge/Source-Provenance.md)和[验收报告](vault/Results/Reports/p1-rkhma-reassembly.md)。
原版与重组版的初始优化、完整执行及截止行为对照见[分阶段报告](vault/Results/Reports/p1-rkhma-phase-comparison.md)。
A3初始化调整、B/C试评和改前回归见[试评接口报告](vault/Results/Reports/p1-abc-trial-interface.md)。
统一接口、原版对照与耗时边界见[RKHMA统一报告](vault/Results/Reports/p1-rkhma-unified.md)。
8个手工组合在三个可枚举实例上的验收见[手工实例报告](vault/Results/Reports/p1-small-enumeration.md)。

## 评价候选解

调用方从当前 `ProblemInstance` 中选取任务、窗口和起止姿态记录，构造
`ScheduledTask` 和 `SchedulingPlan`，再调用
`ScheduleValidator.evaluate(instance, plan)`。

仅在结果 `feasible=true` 时使用 `totalProfit`。
非法方案返回违规项，收益字段为 0。
加载后的输入对象按只读使用。
验证器拒绝使用原始首尾记录作为观测起点或终点。

`method.ProblemAdapter` 提供单窗内部网格与候选迭代。下例的 `instance` 和 `window` 分别是已加载的问题实例及其中一个窗口：

```java
var adapter = new method.ProblemAdapter(instance);
var grid = adapter.grid(window);
var records = grid.records(); // 原始姿态记录去掉首尾后的只读列表
for (var candidate : adapter.candidates(window)) {
  var result = ScheduleValidator.evaluate(instance, new SchedulingPlan(List.of(candidate)));
}
```

`grid.records()` 不可修改，`grid.stepSeconds()` 在内部记录不足两条时为空。
候选迭代只检查单次观测的内部记录、时长和窗口；多个观测组成的完整调度仍须独立验证任务唯一性、重叠及姿态转换。
P1任务1的测试与真实场景检查见[验收报告](vault/Results/Reports/p1-problem-adaptation.md)。

问题定义与输入约定见 [问题建模](vault/Knowledge/问题建模.md)。
知识库入口见 [vault 索引](vault/02-Index.md)。
来源与迁移范围见 [来源记录](vault/Knowledge/Source-Provenance.md)。
开发边界见 [AGENTS.md](AGENTS.md)。

## 独立派生项目

四智能体通用启发式种群演化路线已迁至同级项目
[agentic_heuristic_design](../agentic_heuristic_design/README.md)。
该路线的设计与演示在独立项目维护。
