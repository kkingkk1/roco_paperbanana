# 案例库

本目录保存A/B/C模块的案例及后台材料记录。
案例的字段、分类、去重、来源关联和接口检查见[案例入库与接口适配](../vault/Knowledge/案例入库与接口适配.md)。

- `A/`、`C/`：分别保存对应模块的案例，每条案例使用独立目录。
- `B/initial_single_satellite_optimization/`：初始单星优化案例，编号从`BI-001`开始。
- `B/post_reassignment_adjustment/`：重分配后单星局部调整案例，编号从`BR-001`开始。
- `backend/材料台账.md`：已核查材料、待查材料和搜集方向。
- `backend/legacy_B_cases/`：拆分或改造前的B来源案例，不计入阶段子库。
- `backend/sources/`：按`case_id`和案例版本记录来源与使用条件。
- `backend/versions/`：保存案例历史JSON快照及必要的历史源码快照。
- `backend/archives/`：保存按旧案例编号与相对路径组织的历史源码归档；归档内记录源码和快照哈希。
- `backend/semantic-review.json`：记录已在同组比较的案例版本及其决策区别，供新检索核对。

截至2026-11-17，A/B/C案例库共登记149条本地案例：A为29条，B为40条，C为80条。B的40条包括初始单星优化30条和重分配后单星局部调整10条。现有版本已按决策逻辑复核，未确认仅改名或数值参数的重复项；范围与限制见[复核报告](../vault/Results/Reports/case-semantic-review-20261117.md)。2026-11-18用户确认先用这批案例，按先导结果决定是否扩充。各批次、方法覆盖和缺口见[后台材料台账](backend/材料台账.md)。

用户于2026-11-14确认现有149条入库案例可用于本项目的外部模型检索和代码生成，不要求另交许可证文件。每条案例按当前版本关联后台来源，既有来源审核记录保留。实际候选数量仍取决于案例完整性、模块适用性及去重；来源记录见[后台材料台账](backend/材料台账.md)。

程序构造Retriever请求时，读取本次候选已保存的编号和描述，不默认发送全部候选的完整源码；选中后，程序取得对应案例的代码和适配说明，供所属模块的代码智能体参考。本地读取和校验源码不等于发送源码；完整流程见[案例与生成协议](../vault/Knowledge/多智能体算法与评价合同.md#13-案例与生成协议)。

来源和许可记录留在后台；源码需要保留的作者及著作引用随代码保存。经用户确认，A-018至A-027及A-029的独立许可证文件已删除；A-028的历史`pyproject.toml`含许可字段，当前源码清单不再读取它。Python检索入口已能对人工复核的候选关联来源、实际调用并冻结案例包；正式角色接入仍未完成。

B案例按实际阶段分别存入对应子目录，`sub_library`标记须与目录一致；后台保留的原始版本不计入两个子库。两个阶段的案例供B智能体分别参考，不要求同源或配对；生成的完整B须分别实现初始优化和重分配后调整。材料入口和缺少关键实现的策略包装不计入案例数。

所有现有及后续入库的A/B/C案例，`description`统一使用英文。中文案例索引见[后台材料台账](backend/材料台账.md)。

案例JSON保存`description`片段及源码清单；单文件源码与JSON并列，多文件源码在案例目录的`src/`下保留原相对路径。清单记录文件顺序、相对路径与SHA-256。读取时须校验并恢复完整说明和代码；具体格式见[案例入库与接口适配](../vault/Knowledge/案例入库与接口适配.md#实际保存位置)。

现有149条案例的当前源码清单均使用Python。原Java源码和历史版本保留供对照。A-010是RKHMA思想的Python适配版，不是完整来源协调器；A-019依据来源思想重新实现，节点限制不同于原版回溯次数限制。语言统一不等于这些案例已接入项目的两阶段接口，也不能单独证明算法语义不同。

A-010、BI-030、BR-010和C-003的当前清单各指向一个独立实现文件。它们依据RKHMA的对应思想适配项目接口，不声称逐步复刻来源实现；原多文件源码仍留在各案例的`src/`中供历史版本回查，不由当前清单读取。

在项目根目录调用案例读取器：

```python
from satellite_scheduler.cases import discover_cases, read_case

example = read_case("cases/A/A-001/A-001.json")
description = example.description
code = example.code
case_version = example.case_sha256
b_example = read_case("cases/B/initial_single_satellite_optimization/BI-001/BI-001.json")
b_sub_library = b_example.sub_library
all_cases = discover_cases("cases")
```

先设置 `$env:PYTHONPATH = 'src/main/python'`。读取器校验案例目录身份、源码路径和文件哈希，并保留源码原有空白；文件缺失、格式错误或校验失败时报告异常。`BI`对应`initial`，`BR`对应`post_reassignment`；新编号与子库标记不匹配时报错，无标记的历史材料返回空值。历史快照若使用源码清单，可调用`read_case(snapshot_path, case_directory)`指定案例目录。源码已变化时，按后台来源记录查找对应历史源码；例如C-003记录了`parent_case_directory`。A-018至A-027及A-029中引用已删除许可证文件的历史快照，须恢复相应历史文件后才能完整读取。如果快照编号与案例目录不同，先从后台来源记录确认对应关系，再传入`historical_case_id`。旧版内嵌代码行数组的快照不能通过该接口读取。读取器不加载后台来源，也不判断外发许可。

当前复核组的17份C案例旧Java单文件已按用户确认的清单从原目录移除，逐字保存在[历史源码归档](backend/archives/c-python-parity-20261117.zip)。读取这些历史快照时，可显式指定归档，例如：

```python
old = read_case(
    "cases/backend/versions/C-060/c7d11e4faf0c93fd9672463ba0fc89d66cb3c7040946ac816fb9551b46e79bd2.json",
    "cases/C/C-060",
    source_archive="cases/backend/archives/c-python-parity-20261117.zip",
)
```

归档路径只用于历史快照，不会作为当前案例源码的自动回退。读取器检查案例编号、相对路径、归档条目唯一性和历史快照中的源码哈希。归档内源码保持原样，C-003的其他多文件历史源码仍在原目录。

`discover_cases("cases")`扫描A/B/C当前案例，校验模块目录、B阶段目录和案例身份；遇到重复ID会报错，不会任意保留一条。`b_required_count`和`validate_b_selection`可计算已过滤B候选的返回数量，并校验选中ID来自两个子库且无重复。这些基础函数不读取后台来源，也不判断不同ID的算法语义是否重复。新建Python检索运行时，入口另查[语义复核记录](backend/semantic-review.json)和来源；当前记录149条已按模块和阶段比较的案例版本。案例内容变化或新增案例时，须按决策逻辑重新比较后更新记录。历史冻结包仍按原记录恢复，见[真实检索证据](../vault/Results/Evidence/p2-retriever-python/evidence.md)。

每条`description`说明该案例做什么、相关规则的直接目的或取舍。来源案例以核心思想为参照，可按项目问题和接口适配；写出的具体规则须符合案例代码与适用的运行合同。具体写法见[案例描述标准](../vault/Knowledge/案例入库与接口适配.md#案例描述标准)。共用的问题表示和迁移条件见[A模块](../vault/Knowledge/案例入库与接口适配.md#a模块案例的共用适配边界)、[B模块](../vault/Knowledge/案例入库与接口适配.md#b模块案例的共用适配边界)和[C模块](../vault/Knowledge/案例入库与接口适配.md#c模块案例的共用适配边界)的适配边界。
