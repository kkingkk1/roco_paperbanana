# 知识库状态

更新日期：2026-11-05。

## 项目定位

多卫星成像调度问题的输入表示、完整候选域、物理约束和原始收益目标已确认。
问题层按既定合同保持方法无偏置。
方法层研究五角色协作演化完整A/B/C调度算法，在统一求解条件下评价相对手工起点的收益与实际成本。
实例求解只运行冻结代码。
阶段状态见[主计划](Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)，共享方法与评价细节见[方法合同](Knowledge/多智能体算法与评价合同.md)。P1验收见[P1阶段计划](Plan/mechanisms/P1-可运行求解器起点.md)，后续任务见[P2阶段计划](Plan/mechanisms/P2-五角色协作演化.md)。

## 当前知识覆盖

- [问题建模](Knowledge/问题建模.md)：输入、观测候选域、收益及姿态转换约束。
- [来源记录](Knowledge/Source-Provenance.md)：代码迁移范围、首尾规则与指定数据生成器。
- [算法设计方法参照](Knowledge/算法设计方法参照.md)：已有文献核查及项目迁移边界。
- [方法与评价合同](Knowledge/多智能体算法与评价合同.md)：已确认的接口、运行、演化、数据及评价规则。
- [主计划](Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)：六部分研究主计划及P0—P4阶段路线；各阶段计划见[总索引](02-Index.md)，当前任务与验收见[P1阶段计划](Plan/mechanisms/P1-可运行求解器起点.md)。
- [初始化验收](Results/Reports/problem-template-initialization.md)：既有16项测试和单个真实场景的证据。
- [P1问题层适配](Results/Reports/p1-problem-adaptation.md)：内部网格、候选迭代与首尾检查的验收。
- [P1两阶段运行时](Results/Reports/p1-two-stage-runtime.md)：8个手工组合、资源限制与真实场景烟测。
- [P1完整RKHMA重组](Results/Reports/p1-rkhma-reassembly.md)：来源对照、模块互换及限时真实场景验证。
- [P1手工实例枚举](Results/Reports/p1-small-enumeration.md)：三个实例的完整枚举与8个手工组合复核。
- [RKHMA分阶段对照](Results/Reports/p1-rkhma-phase-comparison.md)：初始单星搜索、来源重组、截止行为及当时的通用接口缺口；当前状态见统一接口报告。
- [P1试评接口](Results/Reports/p1-abc-trial-interface.md)：A3初始化、B/C多候选试评与改前对照。
- [P1独立阶段策略](Results/Reports/p1-independent-policies.md)：A/B各阶段及手工策略的独立实现与改前对照。
- [P1 RKHMA统一接口](Results/Reports/p1-rkhma-unified.md)：统一A/B/C、重分配校准、来源对照和截止检查点。
- [讲解材料](Writing/演化机制示例与汇报提纲.md)：两代假设示例与PPT提纲。
- [进度入口](01-Research-Plan-and-Progress.md)、[总索引](02-Index.md)。

## 使用边界

- P1的固定组合、三个手工实例和RKHMA统一接口已完成工程验收。真实数据检查只覆盖各自记录的场景，不代表其他场景均已验证；通用路径在300任务对照中触及搜索截止。
- 原始记录只读保留；内部记录定义完整候选域，不重采样、不插值。单日时间、精确时长和原转换模型沿用问题合同。
- 已有8个手工A/B/C组合；尚无五角色协作演化系统，也未开展方法比较实验。
- 实际24例数据尚未生成验收；生成器来源确认不等于数据已就绪。
- 正式消融、精确求解与增量加速不是当前起点的完成条件。

问题知识变化时修正Knowledge；计划变化时修正对应P阶段；运行证据保存在Results。本页仅维护覆盖与使用边界。
