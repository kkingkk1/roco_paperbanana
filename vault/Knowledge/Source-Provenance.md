# 模板来源与迁移范围

创建日期：2026-10-05。
知识库初始化补齐日期：2026-10-06。

来源目录：`E:\OneDrive\project\static_determine\new_hybrid`。
来源分支：`main`。
来源 HEAD：`ca0e05acd1f31cac7e53d7f44d5d7744d90583c5`。
来源无已配置远端。

本次读取的是该目录当前工作树，不是假定完全等同于 HEAD 的快照。
开始时存在用户的计划文档修改和一个已删除的临时文件。
这些现有变更均留在来源目录，未迁入模板。

## 保留与抽取

- `model/Task.java`、`model/AttitudeRecord.java`：保留原数据字段与接口。
- `model/TimeWindow.java`：保留窗口与姿态数据接口，移除基于额外记录数量的算法剪枝方法。
- `utils/TimeUtils.java`：保留原姿态转换物理公式与边界判断。
- `utils/DataLoader.java`：保留输入列序、奇数行任务协议、文件编号、窗口和姿态分组关系。移除场景缓存与运行时开关，显式处理任务 ID 0。格式错误和数量不符改为停止加载。
- `Satellite`：只存卫星输入窗口，不持有初始或中间任务分配。
- `ScheduledTask`、`SchedulingPlan`：改为显式观测决策与观测集合，不保留任务编码、种群或适应度缓存。
- `ProblemInstance`、`ScheduleValidator`：新建实例索引与完整候选解的物理约束验证。
- `InspectScene`：新建数据检查入口，要求显式场景路径和规模。

共享 `Scene` 作为外部只读输入，不复制数据。
来源项目的数据对象及物理公式不代表其求解方法必须保留。
模板使用同一问题收益定义，但不要求与原贪心解码器的搜索可达集合一致。

## 排除项

未复制算法库、混合核心、策略适配层、搜索与插入算子、初始分配、动态重分配、算法运行入口、随机键编解码、精英档案、调参和 seed 配置、旧实验脚本、旧测试与方法研究计划。

未复制 `.git/`、`target/`、`outputs/`、IDE 配置、本地 agent 状态、代码图缓存、旧论文和历史实验结果。
没有引入 `slack bonus` 或窗口偏好奖励。
模板初始化时没有建立 Git 历史，也没有 commit、push 或建立远端。

## 问题建模的指定参照

用户于2026-10-22指定前项目 [问题建模](<../../../new_hybrid_problem_template - 副本/docs/问题建模.md>) 作为当前研究问题定义的来源参照。
该文件位于 `E:\OneDrive\project\static_determine\new_hybrid_problem_template - 副本\docs\问题建模.md`。
本项目的 [问题建模](问题建模.md) 沿用其中的收益定义、精确成像时长、姿态转换模型和输入协议。观测候选域按用户已确认的建模定义，仅包含排除原始首尾后的内部等间隔记录。
执行时以本项目维护的问题合同为准；上述参照说明不改变前文记录的代码来源与迁移范围。

## 首尾姿态候选规则的补充参照

2026-10-23用户确认参照 `E:\OneDrive\project\static_determine\satellite-basin-hopping` 的首尾排除规则；2026-10-28进一步澄清，该规则属于问题建模，排除首尾后的内部记录定义完整观测候选域。
该项目当前工作树的 [CandidatePreprocessor](../../../satellite-basin-hopping/src/main/java/algorithm/basinhop/CandidatePreprocessor.java) 只对每个窗口的原始索引1至N−2检查内部采样间隔，并将观测候选起终点限制在这些记录内。
原始姿态列表保留完整N条；[边界回归测试](../../../satellite-basin-hopping/src/test/java/utils/AttitudeBoundaryAlgorithmsTest.java) 同时覆盖首尾排除和原列表不变。
本项目未迁入其求解实现。原始数据全部保留，候选生成与独立验证器均按内部记录定义检查。
P1已实现只读内部网格、单窗候选迭代和验证器首尾排除；改前实现及同输入对照见[验收证据](../Results/Evidence/p1-problem-adaptation/evidence.md)。
来源实现按1秒内部步长检查；本项目仍按已确认合同读取输入步长，不将1秒写成新的通用问题约束。

## RKHMA 方法借鉴

2026-11-04用户确认：在P1的8个手工组合中借鉴RKHMA的分配、局部搜索和插入做法，不额外复现完整RKHMA。
以下参照均为 `satellite-basin-hopping` 的本机工作树；本项目按现有问题合同重新实现，没有复制其算法或实验产物。

- [MaxWindowCountStrategy](../../../satellite-basin-hopping/src/main/java/operator/assignment/MaxWindowCountStrategy.java) 与 [MinTaskCountStrategy](../../../satellite-basin-hopping/src/main/java/operator/assignment/MinTaskCountStrategy.java)提供机会数量和负载分配思路。本项目使用合法候选数及窗口长度归一化负载，不引入容量硬裁剪。
- [ReassignmentOrchestrator](../../../satellite-basin-hopping/src/main/java/main/ReassignmentOrchestrator.java) 与 [InsertionOrderLocalSearcher](../../../satellite-basin-hopping/src/main/java/algorithm/twostage/InsertionOrderLocalSearcher.java)提供转移、交换和局部重插思路。本项目在受影响星副本上编辑，并按完整方案的原始收益严格接受或撤销。
- [GreedyInsertion](../../../satellite-basin-hopping/src/main/java/operator/insertion/GreedyInsertion.java) 与 [MinTransitionInsertion](../../../satellite-basin-hopping/src/main/java/operator/insertion/MinTransitionInsertion.java)提供可行插入和转换代价思路。本项目分别选择最早结束或最小新增转换时间，并保留完整内部候选域。

不迁入来源实现的额外 slack 奖励、固定1秒索引假设或完整种群混合搜索；这些手工策略不是完整RKHMA复现。

## 完整RKHMA的后续迁移

2026-11-04用户进一步要求迁入完整RKHMA，并可将其拆分、重组及与手工A/B/C互换。此要求取代上一节仅借鉴的范围；上一节仍记录原手工策略的来源。
2026-11-05用户确定所有规模采用来源不超过500任务时的做法，暂不为来源初始跨星调整另设兼容路径。A3对全部规模使用复合初始化策略7；超过500任务不再采用来源的策略8及其跨星修补。B3保留初始单星混合搜索及重分配后的局部调整；默认初始种群搜索次数为2，移除来源针对4000及以上任务的搜索缩减与延迟校准分支。
既有[300任务分阶段对照](../Results/Reports/p1-rkhma-phase-comparison.md)未执行超过500任务分支的初始跨星调整，不能证明该调整的收益，也不能证明后续重分配不够强。该机制的受控比较不作为P1前置条件。

完整RKHMA参照 `E:\OneDrive\project\static_determine\satellite-basin-hopping` 当前工作树，分支 `main`，HEAD 为 `fda0333454f8273d726e136d7ed67b3207754090`。该工作树含未提交修改；逐文件来源与迁入哈希见[迁移清单](../Results/Evidence/p1-rkhma-reassembly/manifest.json)。
参照使用显式固定配置 `410000`，即 DE/rand/1 与 PSO 的完整混合路径，包含来源初始分配、种群搜索、贪心解码、四类重分配、热启动与校准。

源码位于独立的 `method.rkhma.legacy` 包。来源模型复制为运行期工作对象，并映射回本项目的原始任务、窗口和姿态对象；共享问题层和Scene不被修改。
1秒内部记录夹具使用来源GREEDY并与来源按相同输入、种子和预算对照。其他内部间隔使用原记录与精确时长构造可行插入；该适配不保证复现来源的窗口偏好和搜索轨迹。
来源优化器使用微小 slack 奖励区分同收益候选。这个评分只用于其内部搜索；最终合法性与原始收益由本项目验证器计算。

为按项目5秒合同返回已有合法解，限时入口提前保存来源首个基准种群个体对应的调度。在已记录的对照条件下，搜索完成时返回来源协调流程结果。来源重分配可能触发全星校准，该行为在RKHMA组合中保留。
RKHMA的A/B/C现直接实现`SolverContracts`，来源协调器保留同一组件的快速执行路径。250任务完整对照和300任务截止差异见[统一证据](../Results/Evidence/p1-rkhma-unified/evidence.md)。来源500以上的原初始修补已按用户决定移除，因此不能把大规模结果称为原算法逐步复现。
来源通用父算法库中的可选 SHADE 特征交叉依赖 Apache Commons Math；固定 `410000` 不调用该分支，本次未迁入该分支，也未新增生产依赖。旧调参文件、随机种子表和实验结果未迁入。

## 本轮实验数据的指定生成器

2026-10-25用户指定 `E:\OneDrive\project\static_determine\Scene\敏捷卫星实验数据生成代码` 作为本轮实验数据生成来源。
其C#入口为 [Form1.cs](../../../Scene/敏捷卫星实验数据生成代码/敏捷卫星实验数据生成代码/算法框架/算法框架/Form1.cs)，工程目标为.NET Framework 4.8。
源码提供任务数、卫星数及区域/全球分布模式；任务生成后调用包内 `eph.exe` 与 `CC4_1.exe`，输出本项目输入协议所用的任务、逐星窗口与姿态文件。
区域模式的原始范围是北纬3°—53°、东经73°—133°；全球模式为纬度−53°—53°、经度−180°—180°。
任务收益在1—10之间随机生成，精确成像时长在15—29秒之间随机生成；这些是当前生成器的任务属性分布，不回写为问题层约束。
源码按当前毫秒播种，并向固定相对目录覆盖输出；本项目须在隔离副本中完成显式种子和批量运行适配。
生成器来源已确认，实际24例数据、运行兼容性与加载验收尚未完成；具体任务归属 [P3阶段计划](../Plan/mechanisms/P3-小预算先导与冻结选型.md)。
本节只补充新实验数据来源，不改变前文记录的Java模板代码来源，也不修改共享Scene中的原始程序或数据。

## 已知表示边界

日期仍按来源代码转换为当日秒数。
用户于2026-10-22确认使用输入网格，并于2026-10-23明确每窗候选排除原始首尾记录；等距要求只适用于剩余内部序列。
本轮方法候选引用内部序列对应的原始姿态对象，不重采样、不改变内部步长、不进行连续时间姿态插值。
原始反向窗口保留，但任何使用它的成像观测均非法。
加载后的输入对象必须按只读使用。

物理约束与原输入语义详见 [问题建模](问题建模.md)。
本项目的知识库结构按 project-creator 初始化，入口见 [总索引](../02-Index.md)。
初始化只记录问题合同及验收，不迁入来源项目的方法研究计划。
