# 案例库

本目录保存A/B/C模块的正式案例及后台材料记录。
案例的字段、分类、去重、来源关联和接口检查见[案例入库与接口适配](../vault/Knowledge/案例入库与接口适配.md)。

- `A/`、`C/`：分别保存对应模块的正式案例，每条案例使用独立目录。
- `B/initial_single_satellite_optimization/`：初始单星优化案例，编号从`BI-001`开始。
- `B/post_reassignment_adjustment/`：重分配后单星局部调整案例，编号从`BR-001`开始。
- `backend/材料台账.md`：已核查材料、待查材料和搜集方向。
- `backend/legacy_B_cases/`：拆分或改造前的B来源案例，不计入阶段子库。
- `backend/sources/`：按`case_id`和案例版本记录来源与使用条件。
- `backend/versions/`：保存案例历史JSON快照及必要的历史源码快照。

截至2026-11-13，A/B/C案例库已初步建成，共登记149条本地正式案例：A为29条，B为40条，C为80条。B的40条包括初始单星优化30条和重分配后单星局部调整10条。各批次、方法覆盖和待补缺口见[后台材料台账](backend/材料台账.md)；各库仍需按约100条的建设目标扩充并复核覆盖。

本地正式案例数不等于可发往外部模型的合格候选数。截至上述日期，每条案例当前版本的后台来源记录至少有一项`external_model_use`为`unknown`；按现行规则，向外部模型发送代码前须先核实相关使用条件。案例读取与B配额校验已实现，使用条件过滤、完整Retriever请求及案例包冻结仍待实现。

B案例按实际阶段分别存入对应子目录，`sub_library`标记须与目录一致；后台保留的原始版本不计入两个子库。两个阶段的案例供B智能体分别参考，不要求同源或配对；生成的完整B须分别实现初始优化和重分配后调整。材料入口和缺少关键实现的策略包装不计入案例数。

所有现有及后续入库的A/B/C案例，`description`统一使用英文。中文案例索引见[后台材料台账](backend/材料台账.md)。

案例JSON保存`description`片段及源码清单；单文件源码与JSON并列，多文件源码在案例目录的`src/`下保留原相对路径。清单记录文件顺序、相对路径与SHA-256。读取时须校验并恢复完整说明和代码；具体格式见[案例入库与接口适配](../vault/Knowledge/案例入库与接口适配.md#实际保存位置)。

在项目根目录调用案例读取器：

```java
import java.nio.file.Path;
import method.cases.CaseReader;

var example = CaseReader.read(Path.of("cases", "A", "A-001", "A-001.json"));
var description = example.description();
var code = example.code();
var caseVersion = example.caseSha256();
var bExample = CaseReader.read(Path.of("cases", "B", "initial_single_satellite_optimization", "BI-001", "BI-001.json"));
var bSubLibrary = bExample.subLibrary();
```

读取器校验案例目录身份、源码路径和文件哈希，并保留源码原有空白；文件缺失、格式错误或校验失败时抛出`IOException`。`BI`对应`initial`，`BR`对应`post_reassignment`；新编号与子库标记不匹配时报错，无标记的历史材料返回空值。历史快照若使用源码清单，可调用`CaseReader.read(snapshotPath, caseDirectory)`指定案例目录。源码已变化时，按后台来源记录查找对应历史源码；例如C-003记录了`parent_case_directory`。源码未变化时可使用当前案例目录。如果快照编号与案例目录不同，先从后台来源记录确认对应关系，再调用`CaseReader.readHistorical(snapshotPath, caseDirectory, historicalCaseId)`。旧版内嵌代码行数组的快照不能通过该接口读取。读取器不加载后台来源，也不判断外发许可。

`method.cases.BCaseSelection`可计算已过滤B候选的返回数量，并校验选中ID来自两个子库且无重复。调用方须先完成来源使用条件等过滤；该工具不判断案例能否发往外部模型，也未实现Retriever调用或案例包冻结。

每条`description`说明该案例做什么、相关规则的直接目的或取舍。来源案例以核心思想为参照，可按项目问题和接口适配；写出的具体规则须符合案例代码与适用的运行合同。具体写法见[案例描述标准](../vault/Knowledge/案例入库与接口适配.md#案例描述标准)。共用的问题表示和迁移条件见[A模块](../vault/Knowledge/案例入库与接口适配.md#a模块案例的共用适配边界)、[B模块](../vault/Knowledge/案例入库与接口适配.md#b模块案例的共用适配边界)和[C模块](../vault/Knowledge/案例入库与接口适配.md#c模块案例的共用适配边界)的适配边界。
