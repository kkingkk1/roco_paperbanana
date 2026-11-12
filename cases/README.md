# 案例库

本目录保存A/B/C模块的正式案例及后台材料记录。
案例的字段、分类、去重、来源关联和接口检查见[案例入库与接口适配](../vault/Knowledge/案例入库与接口适配.md)。

- `A/`、`B/`、`C/`：分别保存对应模块的正式案例，每条案例使用独立目录。
- `backend/材料台账.md`：已核查材料、待查材料和搜集方向。
- `backend/sources/`：按`case_id`和案例版本记录来源与使用条件。
- `backend/versions/`：保存案例历史JSON快照。

A库已开始入库；各库实际数量、批次、覆盖和缺口见[后台材料台账](backend/材料台账.md)。材料入口和缺少关键实现的策略包装不计入案例数。空目录在首次保存案例或来源记录时也可按上述路径创建。

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
```

读取器校验案例目录身份、源码路径和文件哈希，并保留源码原有空白；文件缺失、格式错误或校验失败时抛出`IOException`。历史快照若使用源码清单，可调用`CaseReader.read(snapshotPath, caseDirectory)`指定案例目录；旧版内嵌代码行数组的快照不能通过该接口读取。读取器不加载后台来源，也不判断外发许可。

每条`description`说明该案例做什么、相关规则的直接目的或取舍。来源案例以核心思想为参照，可按项目问题和接口适配；写出的具体规则须符合案例代码与适用的运行合同。具体写法见[案例描述标准](../vault/Knowledge/案例入库与接口适配.md#案例描述标准)。多个案例共用的问题表示和迁移条件集中记在[A模块共用适配边界](../vault/Knowledge/案例入库与接口适配.md#a模块案例的共用适配边界)。
