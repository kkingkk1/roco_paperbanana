# 研究计划与进度

更新日期：2026-11-07。

## 项目目标

面向已确定的多卫星成像调度问题，通过五角色协作演化完整A/B/C调度算法。
在统一求解条件下，评价其相对手工起点的收益与实际成本。
最终部署运行冻结的A/B/C代码，收益与机制贡献由相应实验支持。

## 执行入口

[主计划](Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)维护路线和阶段状态；状态只在其第四节维护。本页只提供入口，不另列一套里程碑或待办状态。
共享合同见[方法与评价合同](Knowledge/多智能体算法与评价合同.md)，决定时点见[风险与待决事项](Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md#六风险与待决事项)。

- [P0：研究合同与既有起点](Plan/mechanisms/P0-研究合同与既有起点.md)
- [P1：可运行求解器起点](Plan/mechanisms/P1-可运行求解器起点.md)——工程验收已完成，阶段状态见主计划。
- [P2：五角色协作演化](Plan/mechanisms/P2-五角色协作演化.md)
- [P2案例与检索子计划](Plan/mechanisms/P2-任务1-准备案例与检索协议.md)及[材料与入库规则](Knowledge/案例入库与接口适配.md)：材料盘点与规则已完成，A案例库已开始建设；实际条数、来源限制和缺口见[后台台账](../cases/backend/材料台账.md)。B/C案例及检索实现仍待完成。
- [P3：小预算先导与冻结选型](Plan/mechanisms/P3-小预算先导与冻结选型.md)
- [P4：正式实验与研究主张](Plan/mechanisms/P4-正式实验与研究主张.md)

## 证据与来源

- [初始化验收](Results/Reports/problem-template-initialization.md)及[manifest](Results/Evidence/problem-template-initialization/manifest.json)：可复用的问题层起点，不代表新首尾检查或求解方法已实现。
- [P1问题层适配](Results/Reports/p1-problem-adaptation.md)及[证据](Results/Evidence/p1-problem-adaptation/evidence.md)：内部网格、候选迭代、首尾排除和同输入对照。
- [P1两阶段运行时](Results/Reports/p1-two-stage-runtime.md)及[证据](Results/Evidence/p1-two-stage-runtime/evidence.md)：手工组合、资源限制、超时与真实场景烟测。
- [P1完整RKHMA重组](Results/Reports/p1-rkhma-reassembly.md)及[证据](Results/Evidence/p1-rkhma-reassembly/evidence.md)：来源对照、模块互换与限时场景验证。
- [P1手工实例枚举](Results/Reports/p1-small-enumeration.md)及[证据](Results/Evidence/p1-small-enumeration/evidence.md)：三个实例的完整枚举与24次组合输出复核。
- [RKHMA分阶段对照](Results/Reports/p1-rkhma-phase-comparison.md)及[证据](Results/Evidence/p1-rkhma-phase-comparison/evidence.md)：来源初始单星搜索、截止差异与当时的通用接口缺口。
- [P1试评接口](Results/Reports/p1-abc-trial-interface.md)及[证据](Results/Evidence/p1-abc-trial-interface/evidence.md)：A3初始化、受控试评与改前对照。
- [P1独立阶段策略](Results/Reports/p1-independent-policies.md)及[证据](Results/Evidence/p1-independent-policies/evidence.md)：A/B阶段入口和手工策略分离、改前对照。
- [P1 RKHMA统一接口](Results/Reports/p1-rkhma-unified.md)及[证据](Results/Evidence/p1-rkhma-unified/evidence.md)：A/B/C统一、来源对照和截止检查点。
- [问题建模](Knowledge/问题建模.md)与[来源记录](Knowledge/Source-Provenance.md)：问题合同及指定生成器。
- [算法设计方法参照](Knowledge/算法设计方法参照.md)：既有文献核查与迁移依据。
- [演化机制示例与汇报提纲](Writing/演化机制示例与汇报提纲.md)：假设成绩与讲解材料，不是实验结果。

实际运行结果进入`Results/Evidence/`与`Results/Reports/`，原始现场进入`outputs/`；有新结果时在本页链接相应证据，不重复抄写主计划。
