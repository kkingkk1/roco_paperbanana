# RoCo 与 PaperBanana 多智能体调度算法研发

本项目面向已确定的多卫星成像调度问题，结合RoCo与PaperBanana的多智能体思想，演化完整A/B/C调度算法。
在统一求解条件下，评价其相对手工起点的收益与实际成本。
智能体在研发阶段检索方法、规划设计、生成代码、分析执行结果并协作改进，最终运行生成并冻结的求解器。

当前已完成问题输入、只读内部网格、单窗候选迭代、物理约束检查和总收益评价。
联合多智能体系统与求解方法尚未实现，以下入口用于读取问题和评价外部候选解。
P1的问题层适配已完成；两阶段运行时与手工策略待实现。

## 研究路线

离线演化采用Retriever、A任务分配、B单星优先级与局部搜索、C调度解码和Critic五类角色。
每轮A/B/C基于同一完整父版本修改各自模块，程序统一评价全部8种新旧搭配，Critic统一分析，程序选择下一轮父版本并在代末取Top N。
Retriever每次独立演化只初始化检索一次；A/B/C分别保留反思与长期记忆，协同经验另存。

冻结代码求解实例时，由程序组织初始化，再基于已有调度反复重分配并局部调整。每个A/B/C版本都支持两个阶段，实例内不调用LLM。
后台采用候选记录、评价记录和运行级公共信息；公共信息每次运行只存一份，案例`source`仅留后台。

阶段状态与路线见[主计划P0—P4](vault/Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)，接口、先导配置及评价规则见[方法与评价合同](vault/Knowledge/多智能体算法与评价合同.md)。
当前实施入口是[P1：可运行求解器起点](vault/Plan/mechanisms/P1-可运行求解器起点.md)；后续实验按阶段进入条件开展。
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
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' `
  1000 10
```

该入口只报告数据规模和空方案的合法性，不生成调度。

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
