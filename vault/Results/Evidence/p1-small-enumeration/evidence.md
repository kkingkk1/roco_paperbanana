# P1手工实例枚举证据

日期：2026-11-05。

## 验收范围

三个固定手工实例覆盖跨星同时观测、同星冲突、0.5秒内部记录、无候选任务、观测顺序和姿态转换临界值。枚举代码只用于这三个夹具，不是通用精确求解器。

- 工作目录：`E:\OneDrive\project\static_determine\roco_paperbanana`。
- 合同：[问题建模](../../../Knowledge/问题建模.md)和[方法与评价合同](../../../Knowledge/多智能体算法与评价合同.md)。
- 环境：Windows，OpenJDK 21.0.4，Maven 3.9.11，PowerShell 7；运行保持 Normal 优先级。
- 输入：手工构造的任务、卫星、窗口与原始姿态记录，不读取或修改共享Scene。固定求解种子42；不使用随机生成的测试数据。
- 合同限制：求解逻辑使用默认预算数值，注入恒为0的时钟以完整检查小实例。此方法不验证真实墙钟、CPU或内存限制。
- 代码与数据版本：[manifest.json](manifest.json)。24次运行的实际观测和计数：[data.csv](data.csv)。

## 枚举与独立核对

Java验收程序是[固定夹具及枚举程序](../../../../src/test/java/method/runtime/P1EnumerationAudit.java)。它先按各夹具手写的任务、卫星、窗口和原始姿态记录位置列出候选，再与 `ProblemAdapter` 的结果逐项比较，包括窗口及姿态对象身份。每个任务选择不观测或一个手工候选，逐一枚举完整选择。

夹具姿态差只使用0°和30°：按照问题合同，转换需要11.66秒或25秒。独立计算不调用 `TimeUtils` 或 `ScheduleValidator`；每个完整选择再与 `ScheduleValidator` 的可行性和收益逐一比对。

- `contest`：3任务、2卫星，6候选，27种完整选择、13种可行，最优收益14。同一任务在两颗卫星上的机会属于相互排斥的选择。
- `half_second`：4任务、2卫星，9候选，60种完整选择、32种可行，最优收益24。内部记录间隔为0.5秒；任务3有原始窗口但内部记录不足以满足3秒成像时长，因此没有候选。
- `slew_boundary`：4任务、1卫星，4候选，16种完整选择、10种可行，最优收益15。任务0结束后到任务1只有24秒，小于30°所需25秒；到任务2恰好25秒，满足转换要求。

共103种完整选择中，55种可行、48种不可行，独立计算与项目验证器逐项一致。对每个实例的每个组合，单独检查实际观测属于手工候选、不重复任务、满足卫星转换约束，重新计算收益，并检查收益不超过该实例枚举最优值。

[independent_oracle.py](independent_oracle.py)使用独立手写候选和Python标准库再次穷举，不导入项目代码。脚本还逐条复核 `data.csv` 中的24个实际调度。以内存副本分别篡改收益和卫星候选时，复核器均按预期拒绝。

## 实际命令与结果

```powershell
mvn -o '-Dtest=P1EnumerationAcceptanceTest' test
java -cp 'target/classes;target/test-classes' method.runtime.P1EnumerationAudit `
  'vault/Results/Evidence/p1-small-enumeration/data.csv'
python 'vault/Results/Evidence/p1-small-enumeration/independent_oracle.py' `
  'vault/Results/Evidence/p1-small-enumeration/data.csv'
mvn -o test
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10
```

Java验收程序仅在目标CSV不存在时创建文件；重放时请给出新的输出路径，再将该路径传给Python复核器。

针对性测试1项通过。Python独立输出为 `contest: 6/27/13/14`、`half_second: 9/60/32/24`、`slew_boundary: 4/16/10/15`，各值依次为候选数、完整选择数、可行选择数和最优收益；24行输出检查通过。

`mvn -o test` 全部56项通过，0失败、0错误、0跳过，覆盖原有55项及新增枚举验收。README真实场景检查得到1000任务、10卫星、2823窗口、444817条姿态记录；空调度合法、收益0。

## 组合结果

- `contest`：8个组合均为收益14。
- `half_second`：A1的4个组合收益24；A2的4个组合收益18。
- `slew_boundary`：8个组合均为收益12，低于最优15。

以上24次均成功，所有观测通过独立复核。三个夹具未产生被接受的重分配；本项不声称覆盖接受移动的路径。[两阶段运行时证据](../p1-two-stage-runtime/evidence.md)和[RKHMA重组证据](../p1-rkhma-reassembly/evidence.md)分别记录了相关阶段、接受与撤销检查。
