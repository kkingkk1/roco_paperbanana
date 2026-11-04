# 研究计划与进度

更新日期：2026-11-04。

## 项目目标

面向已确定的多卫星成像调度问题，通过五角色协作演化完整A/B/C调度算法。
在统一求解条件下，评价其相对手工起点的收益与实际成本。
最终部署运行冻结的A/B/C代码，收益与机制贡献由相应实验支持。

## 执行入口

[主计划](Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)维护路线和阶段状态；状态只在其第四节维护。本页只提供入口，不另列一套里程碑或待办状态。
共享合同见[方法与评价合同](Knowledge/多智能体算法与评价合同.md)，决定时点见[风险与待决事项](Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md#六风险与待决事项)。

- [P0：研究合同与既有起点](Plan/mechanisms/P0-研究合同与既有起点.md)
- [P1：可运行求解器起点](Plan/mechanisms/P1-可运行求解器起点.md)——当前实施入口，阶段状态见主计划。
- [P2：五角色协作演化](Plan/mechanisms/P2-五角色协作演化.md)
- [P3：小预算先导与冻结选型](Plan/mechanisms/P3-小预算先导与冻结选型.md)
- [P4：正式实验与研究主张](Plan/mechanisms/P4-正式实验与研究主张.md)

## 证据与来源

- [初始化验收](Results/Reports/problem-template-initialization.md)及[manifest](Results/Evidence/problem-template-initialization/manifest.json)：可复用的问题层起点，不代表新首尾检查或求解方法已实现。
- [P1问题层适配](Results/Reports/p1-problem-adaptation.md)及[证据](Results/Evidence/p1-problem-adaptation/evidence.md)：内部网格、候选迭代、首尾排除和同输入对照。
- [问题建模](Knowledge/问题建模.md)与[来源记录](Knowledge/Source-Provenance.md)：问题合同及指定生成器。
- [算法设计方法参照](Knowledge/算法设计方法参照.md)：既有文献核查与迁移依据。
- [演化机制示例与汇报提纲](Writing/演化机制示例与汇报提纲.md)：假设成绩与讲解材料，不是实验结果。

实际运行结果进入`Results/Evidence/`与`Results/Reports/`，原始现场进入`outputs/`；有新结果时在本页链接相应证据，不重复抄写主计划。
