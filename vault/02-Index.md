# 项目知识索引

更新日期：2026-11-07。

## 顶层入口

- [项目使用说明](../README.md)
- [项目指令](../AGENTS.md)
- [知识库状态](00-Knowledge-Base-Status.md)
- [研究计划与进度](01-Research-Plan-and-Progress.md)

## 研究计划

[面向成像卫星任务调度的多智能体协作自动算法设计](Plan/面向成像卫星任务调度的多智能体协作自动算法设计.md)是唯一主计划，按目标、现状、总体方案、阶段路线、验收与证据、风险与待决事项六部分组织。P0—P4阶段状态只在其第四节维护。
各阶段计划维护对应任务、交付物与验收：

- [P0-研究合同与既有起点](Plan/mechanisms/P0-研究合同与既有起点.md)
- [P1-可运行求解器起点](Plan/mechanisms/P1-可运行求解器起点.md)——已完成工程验收。
- [P2-五角色协作演化](Plan/mechanisms/P2-五角色协作演化.md)
- [P2-任务1-准备案例与检索协议](Plan/mechanisms/P2-任务1-准备案例与检索协议.md)
- [P3-小预算先导与冻结选型](Plan/mechanisms/P3-小预算先导与冻结选型.md)
- [P4-正式实验与研究主张](Plan/mechanisms/P4-正式实验与研究主张.md)

## 问题与方法知识

- [问题建模与输入协议](Knowledge/问题建模.md)
- [来源、迁移范围与表示边界](Knowledge/Source-Provenance.md)
- [方法与评价合同](Knowledge/多智能体算法与评价合同.md)
- [案例入库与接口适配](Knowledge/案例入库与接口适配.md)及[后台材料台账](../cases/backend/材料台账.md)
- [算法设计方法参照](Knowledge/算法设计方法参照.md)
- [演化机制示例与汇报提纲](Writing/演化机制示例与汇报提纲.md)——讲解用假设成绩，不是运行证据。

## 既有验收

各历史报告保留当时的实现状态。RKHMA接口和P1阶段的当前状态以统一接口报告与主计划为准。

- [P1 RKHMA统一接口报告](Results/Reports/p1-rkhma-unified.md)及[证据](Results/Evidence/p1-rkhma-unified/evidence.md)
- [RKHMA分阶段对照报告](Results/Reports/p1-rkhma-phase-comparison.md)及[证据](Results/Evidence/p1-rkhma-phase-comparison/evidence.md)
- [P1试评接口报告](Results/Reports/p1-abc-trial-interface.md)及[证据](Results/Evidence/p1-abc-trial-interface/evidence.md)
- [P1独立阶段策略报告](Results/Reports/p1-independent-policies.md)及[证据](Results/Evidence/p1-independent-policies/evidence.md)
- [P1手工实例枚举报告](Results/Reports/p1-small-enumeration.md)及[证据](Results/Evidence/p1-small-enumeration/evidence.md)
- [P1完整RKHMA重组报告](Results/Reports/p1-rkhma-reassembly.md)及[证据](Results/Evidence/p1-rkhma-reassembly/evidence.md)
- [P1两阶段运行时报告](Results/Reports/p1-two-stage-runtime.md)及[证据](Results/Evidence/p1-two-stage-runtime/evidence.md)
- [P1问题层适配报告](Results/Reports/p1-problem-adaptation.md)及[证据](Results/Evidence/p1-problem-adaptation/evidence.md)
- [初始化报告](Results/Reports/problem-template-initialization.md)
- [证据说明](Results/Evidence/problem-template-initialization/evidence.md)
- [验证结果表](Results/Evidence/problem-template-initialization/data.csv)
- [验收元数据](Results/Evidence/problem-template-initialization/manifest.json)
- [旧格式初始化Gate](<Plan/gates/问题模板初始化 Gate.md>)——保留的历史记录，当前阶段状态以主计划为准。

## 资料与现场

正式PDF及资料产物保存在`E:\OneDrive\my_library`；Zotero保存题录与本地PDF链接。
`Writing/`中的论文副本和演示材料不替代正式资料。运行现场放`outputs/<run-id>/`，测得结果放`Results/`。

## 独立派生项目

[四智能体通用启发式自动设计](../../agentic_heuristic_design/README.md)在同级独立项目维护。
