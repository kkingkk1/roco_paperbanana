---
plan_id: problem-template-initialization
category: gates
status: complete
created_at: 2026-10-06
updated_at: 2026-10-08
---

# 问题模板初始化 Gate

## 目标与允许范围

建立可复用的问题模板，并按 project-creator 初始化研究知识库。
来源项目只提供读取材料，不在其目录生成新计划或新结果。

初始迁移仅保留问题数据、物理工具、候选解与验证器。
本轮 vault 补齐仅修改 Markdown 与证据文件，不改 Java、测试或构建配置。

## 前提与未知点

当前输入使用单日时间轴与离散姿态记录。
该边界已写入问题合同。
初始化验证使用一个真实场景，不能推导全部场景均已验收。
没有需要补充的方法、seed、搜索预算或性能阈值。

## 执行与验收

- [x] 记录来源目录、HEAD、工作树选择范围和排除项。
- [x] 检查生产源码不依赖来源的算法、策略、插入和重分配模块。
- [x] 执行最小构建与问题层测试，16 项通过。
- [x] 比较真实场景的全部已解析字段与顺序，指纹一致。
- [x] 用真实数据验证显式非空观测及错误成像时长的拒绝结果。
- [x] 建立三份标准 vault 入口与 Plan、Evidence、Reports 路由。
- [x] 检查 vault 文档链接、状态和初始化记录一致。
- [x] 验证本轮文档补齐没有修改既有 Java、测试及 pom.xml。
- [x] 更新交付压缩包，并逐文件检查与当前新项目一致。

## 结果与决策

初始化完成。
本 Gate 验收的是问题层及知识库结构，不包含算法性能评价。
下一次问题合同改变时，新建或更新对应的模型变更 Gate。

## 结果入口

- [初始化报告](../../Results/Reports/problem-template-initialization.md)。
- [初始化证据](../../Results/Evidence/problem-template-initialization/evidence.md)。
- [项目计划与进度](../../01-Research-Plan-and-Progress.md)。
