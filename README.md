# RoCo 与 PaperBanana 多智能体调度算法研发

本项目面向已确定的多卫星成像调度问题，结合RoCo与PaperBanana的多智能体思想，演化完整A/B/C调度算法。
在统一求解条件下，评价其相对手工起点的收益与实际成本。
智能体在研发阶段检索方法、规划设计、生成代码、分析执行结果并协作改进，最终运行生成并冻结的求解器。

项目以Python为主。问题层、两阶段运行时、27种预置组合和案例读取已通过P1工程验收。生成代码的接入与隔离属于P2。
A/B/C案例库已初步建成，实际条数与使用边界见[案例目录](cases/README.md)。
原Java版本已完成P1工程验收，作为迁移对照保留。Python的Retriever和A/B/C生成角色可独立真实调用；组合控制、Critic及生成代码的隔离执行仍在P2。

## 研究路线

离线演化采用Retriever、A任务分配、B单星优先级与局部搜索、C调度解码和Critic五类角色。
每轮A/B/C基于同一完整父版本修改各自模块，程序统一评价全部8种新旧搭配，Critic统一分析，程序选择下一轮父版本并在代末取Top N。
Retriever每次独立演化只初始化检索一次；A/B/C分别保留反思与长期记忆，协同经验另存。

冻结代码求解实例时，统一目标流程为A初始归属、B/C单星搜索，再基于已有调度由A重分配并由B/C更新。每个A/B/C版本都支持两个阶段，实例内不调用LLM。
后台采用候选记录、评价记录和运行级公共信息；公共信息每次运行只存一份。正式案例应按决策逻辑去重，元数据和完整算法源码存于`cases/`；现有149条案例版本已按决策逻辑复核。入库时不要求已适配本项目接口或预先证明收益更高，案例`source`仅留后台。

阶段状态与路线见[主计划P0—P4](vault/Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)，接口、先导配置及评价规则见[方法与评价合同](vault/Knowledge/多智能体算法与评价合同.md)。
历史起点验收和Python迁移任务见[P1：可运行求解器起点](vault/Plan/mechanisms/P1-可运行求解器起点.md)；后续协作演化工作见[P2阶段计划](vault/Plan/mechanisms/P2-五角色协作演化.md)。
[方法参照](vault/Knowledge/算法设计方法参照.md)与[演化示例](vault/Writing/演化机制示例与汇报提纲.md)供理解机制，不替代已确认合同。

## 环境与验证

Python实现目前只使用标准库，本机已用Python 3.13验证。先在项目根目录运行：

```powershell
$env:PYTHONPATH = 'src/main/python'
python -B -m unittest discover -s src/test/python
```

保留的Java对照使用Java 21和Maven，测试沿用JUnit 5.10.0：

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
$env:PYTHONPATH = 'src/main/python'
python -B -m satellite_scheduler `
  'F:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' `
  1000 10
```

该入口只报告数据规模和空方案的合法性，不生成调度。

## 运行调度组合

先执行离线测试，再使用 Windows PowerShell 7 入口运行单个组合：

```powershell
.\scripts\run-python-manual.ps1 `
  -Scene 'F:\OneDrive\project\static_determine\Scene\500-5-0-0-24' `
  -Tasks 500 -Satellites 5 -Combination A1B1C1 -Seed 42
```

Python入口接受 `A1/A2/A3`、`B1/B2/B3`、`C1/C2/C3` 的27种组合。其中8种为纯手工组合，19种含至少一个RKHMA模块。将 `-Combination` 改为 `A3B3C3` 运行完整RKHMA，改为 `A3B1C3` 可替换单星搜索。

- A3在所有规模上按来源策略7执行初始分配，初始化不试探跨星迁移。重分配阶段按实际收益试评跨星操作，接受迁移后可重新校准全部卫星。
- B3沿用固定配置 `410000` 的单星混合搜索，无继承状态的非空卫星首次完整搜索使用两份种群。重分配阶段另做单星修补和校准，无额外初始化局部步骤，也不启用来源仅用于4000及以上任务的搜索缩减与延迟校准。
- C3执行RKHMA贪心解码。A和B可在统一运行时中受控试评多个候选；各阶段职责与校验见[方法合同](vault/Knowledge/多智能体算法与评价合同.md#11-两阶段接口与职责)。

入口使用单核、2 GiB进程组内存上限。公共数据读取完成后，搜索截止为4.5秒，包含结果输出和进程外验证的硬截止为5秒。独立验证进程在截止内重建并检查求解进程的输出。
输出首行给出状态、收益、观测数与计时；后续每行给出任务、卫星、原始窗口序号和精确时刻。
`SEARCH_EXPIRED` 表示搜索截止后返回此前可行调度；`HARD_TIMEOUT` 或 `FAILURE` 表示失败，没有有效收益。
RKHMA在搜索截止时可能只返回来源首个基准种群个体的调度，不能把该结果称为完整搜索的终点。

使用 `scripts/run-manual.ps1` 运行保留的Java对照。直接调用Python或Java运行时不会自动设置操作系统级CPU和内存限制；正式运行使用对应脚本。
Python的27种组合已在小实例上与Java对照，两个真实场景的RKHMA限时对照见[迁移证据](vault/Results/Evidence/python-migration/evidence.md)。后者均在搜索截止时返回，不能替代完整搜索对照。
RKHMA来源、非1秒记录适配与已验证范围见[来源记录](vault/Knowledge/Source-Provenance.md)和[验收报告](vault/Results/Reports/p1-rkhma-reassembly.md)。
原版与重组版的初始优化、完整执行及截止行为对照见[分阶段报告](vault/Results/Reports/p1-rkhma-phase-comparison.md)。
A3初始化调整、B/C试评和改前回归见[试评接口报告](vault/Results/Reports/p1-abc-trial-interface.md)。
统一接口、原版对照与耗时边界见[RKHMA统一报告](vault/Results/Reports/p1-rkhma-unified.md)。
8个手工组合在三个可枚举实例上的验收见[手工实例报告](vault/Results/Reports/p1-small-enumeration.md)。

## 真实案例检索

Python检索入口接收已按决策逻辑复核的案例ID，核对当前版本和后台来源后，使用现有Codex CLI发起一次真实请求，并将请求、回复、用量及固定案例包保存在 `outputs/<run-id>/`。候选提示只包含编号、类别、阶段和描述，不默认发送全部源码。已冻结运行可通过相同 `run-id` 恢复，不会重新检索：

```powershell
$env:PYTHONPATH = 'src/main/python'
python -B -m satellite_scheduler.retriever python-retriever-20261117-134111
```

这次[真实检索证据](vault/Results/Evidence/p2-retriever-python/evidence.md)只使用12条人工复核案例，是小范围联调；后来现有149条版本完成决策逻辑复核，见[复核报告](vault/Results/Reports/case-semantic-review-20261117.md)。不可变模型快照和五角色接入仍未完成。新运行使用新的 `run-id`，通过 `--reviewed-case` 逐条指定候选，并用 `--selection-basis` 记录筛选依据；A、C和B的两个阶段均须有候选。入口检查候选是否列在[语义复核记录](cases/backend/semantic-review.json)中，且版本未发生变化；其他案例须完成实际决策逻辑复核后才能加入。哈希只绑定版本，不证明算法不同。传输失败后的自动重试策略尚未确定。

## A/B/C代码生成

三个角色的提示分别维护：[A任务归属](src/main/python/satellite_scheduler/prompts/allocation.md)、[B单星搜索](src/main/python/satellite_scheduler/prompts/satellite_search.md)、[C时间轴解码](src/main/python/satellite_scheduler/prompts/decoder.md)。程序向各自的提示填入该角色的方法签名、同一份完整P1手工父版本及本模块的冻结案例。

在项目根目录调用一个角色。将`A`换成`B`或`C`可生成相应模块。下例恢复该角色已完成的请求和结果。首次调用一个角色时读取当前提示；要用新提示重新生成已完成的角色，使用新的运行ID。

```powershell
$env:PYTHONPATH = 'src/main/python'
python -B -m satellite_scheduler.generation p2-abc-verified-20261119 A `
  --case-run-id python-retriever-20261117-134111 --parent A1B1C1 --model gpt-6.1-terra
```

请求、原始回复、用量和候选保存在`outputs/<run-id>/generation/<role>/`。`candidate.json`包含生成的`description`、`code`及程序绑定的角色和父版本哈希。入口检查说明结构、Python语法与方法签名；当前以文本保留生成代码。实际组装和隔离执行见[P2阶段计划](vault/Plan/mechanisms/P2-五角色协作演化.md)。

## 评价候选解

Python调用方从当前 `ProblemInstance` 中选取任务、窗口和起止姿态记录，构造 `ScheduledTask`，然后调用 `satellite_scheduler.problem.evaluate(instance, observations)`。
合法时读取 `result.total_profit`；非法时读取 `result.violations`。直接调用时验证发生在调用方进程内；手工运行脚本另用独立进程终验。

Java对照的调用方从当前 `ProblemInstance` 中选取任务、窗口和起止姿态记录，构造
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
