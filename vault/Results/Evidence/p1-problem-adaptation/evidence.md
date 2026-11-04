# P1问题层适配证据

日期：2026-11-04。

## 范围与环境

本次只验收内部网格、单窗候选迭代、实例输入检查和完整调度验证器的首尾排除。
两阶段运行时、手工策略及五角色系统不在本次验收范围。

- 工作目录：`E:\OneDrive\project\static_determine\roco_paperbanana`。
- 问题合同：[问题建模](../../../Knowledge/问题建模.md)。
- 数据：共享 `Scene\1000-10-0-0-12`，只读；手工构造的小实例作为边界对照。
- 环境：Windows，Java 21.0.4，Maven 3.9.11；执行命令的 PowerShell 进程优先级为 Normal，未设置其他进程优先级。
- Maven 使用离线缓存；未安装或下载依赖。
- 任务1验收时，该目录尚无 Git 历史。改前的 `ProblemInstance.java` 与 `ScheduleValidator.java` 已逐字节保存在 [baseline](baseline/)，SHA-256 见 [manifest](manifest.json)。

## 实际命令

初始验收和后续接口简化均执行了完整测试，并按顺序检查真实场景：

```powershell
mvn -o test
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
javac -encoding UTF-8 -cp 'target/classes;target/p1-baseline-comparator/classes' `
  -d target/p1-baseline-comparator/classes `
  'vault/Results/Evidence/p1-problem-adaptation/SceneAudit.java'
java -Xmx2g -cp 'target/classes;target/p1-baseline-comparator/classes' `
  audit.SceneAudit 'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
```

`mvn -o test` 中的 `BaselineComparisonTest` 从保存的源码生成仅更改包名的隔离副本，并编译到 `target/p1-baseline-comparator/classes`。
`ProblemInstance` 副本另补充原包内类型的导入；验证逻辑不变。
`SceneAudit` 使用该副本与当前验证器，分别评价同一候选对象。

## 结果

- 改前完整测试：16项通过，0失败、0错误、0跳过。
- 改后完整测试：25项通过，0失败、0错误、0跳过。原有12项验证器测试的观测已调整到内部记录；4项加载器测试未修改。新增6项适配测试及3项改前对照测试。
- 手工同输入对照：11个内部观测方案的可行性、收益与违规项一致；首条或末条原始记录构成的2个观测由改前接受变为拒绝。内部不等距的实例由改前接受变为输入错误。
- 测试覆盖内部记录顺序、原姿态对象身份、只读列表、内部记录不足两条、非整数步长、精确时长、浮点容差、反向窗口及删除中间观测后的新相邻转换边。
- 真实场景加载：1000个任务、10颗卫星、2823个窗口、444817条原始姿态记录；空方案可行，收益为0。
- 真实场景适配：检查全部2823个窗口及439173条内部记录的索引与对象身份，迭代得到377674个单窗候选。
- 真实数据对照：32个窗口逐对穷举并与迭代结果比较；6823个内部候选由改前与当前验证器使用同一输入评价，结果和收益一致。

## 接口简化复验

用户确认直接使用只读内部记录列表，移除额外的索引名称和原始索引转换接口。简化前后分别使用上述 `SceneAudit` 命令处理同一场景。
全部2823个窗口、439173条内部记录及377674个候选的指纹一致：

`c556ffcf5ffb3bd81f58c2f63483a7d753ddb1122d80c8aa53344d000a4135a7`

指纹按顺序包含卫星与任务、窗口起止、内部姿态记录及候选对应的原始记录位置；检查程序同时核对候选引用的原对象。
它标识本次解析和枚举结果，不是原始数据文件哈希。
简化后完整测试仍为25项通过，真实场景加载与32个窗口的独立穷举均通过。

## 解释与限制

单窗候选只保证观测自身满足内部记录、窗口和时长条件。完整方案的任务唯一性、时间重叠与姿态转换仍由 `ScheduleValidator` 判定。
改前对照与真实窗口穷举的范围如上，不代表所有可能输入均已穷举，也不形成算法收益或性能结论。
原始数据保留在共享 `Scene`，没有写回或重采样。

结果表见 [data.csv](data.csv)，元数据见 [manifest.json](manifest.json)，人读结论见[报告](../../Reports/p1-problem-adaptation.md)。
