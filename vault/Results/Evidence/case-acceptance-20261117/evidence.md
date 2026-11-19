# C案例跨语言验收与去重复核证据

本证据记录2026-11-17的阶段性检查。结论与待处理事项见[报告](../../Reports/case-acceptance-20261117.md)，场景、输出和版本哈希见[manifest](manifest.json)，构造的边界反例见[data.csv](data.csv)。

使用同一场景文件，对80个C案例分别运行Python和旧Java实现。72个场景均为5任务、1星，每任务1至2个窗口；24个场景采用0.5秒内部间隔，其余采用1秒。任务收益覆盖0至15，时长为1至4秒。每场景使用3种任务顺序，分别检查初始构造及4种局部编辑，共86400条输出逐行一致。两侧使用相同的固定随机数序列和不走时的测试预算；本结果不涵盖全部输入，也不说明相同墙钟截止下的搜索进度相同。

原有夹具中有些不同案例恰好给出相同输出。对其中8组构造了不同决策的输入，并直接运行旧Java和当前Python，16条结果一致。例子包括零收益任务是否被额外移除、按总移动距离还是改动数量选方案，以及是否减去被替换的姿态转换边。具体方案、代码和输出见[运行现场](../../../../outputs/case-acceptance-20261117/)。

在原12条人工复核案例的基础上，按实际选择规则与已复核组比较，新增13条C案例。当前复核组为A 4条、B 4条、C 17条；其余124条仍未纳入该组。本轮未确认需要合并的重复案例。不同输出或文件哈希不能单独证明方法语义不同，未完成的案例继续按决策逻辑复核。

对149份当前Python案例，去掉文档字符串后比较完整语法树，没有发现完全相同的条目。进一步把数值常量归一化后得到两组候选：C-011至C-016及C-029至C-031，以及C-044至C-046。检查共用函数发现，变化的数值用于切换未来机会数量、任务覆盖、收益加权或保留比例等不同评分目标，不能按普通数值参数变体合并。扫描命令为`python -B outputs/case-acceptance-20261117/semantic_ast_scan.py`，具体候选见[扫描结果](../../../../outputs/case-acceptance-20261117/semantic-ast-scan.txt)。该扫描不能排除改名、结构改写等语义重复。

当前复核组内17份C案例旧Java源码已逐字保存到[历史归档](../../../../cases/backend/archives/c-python-parity-20261117.zip)，旧元数据和源码哈希均可验证。Python读取器支持显式从归档读取历史快照；Java案例测试直接编译归档中的内容。用户确认后，2026-11-17按[删除清单](../../../../outputs/case-acceptance-20261117/java-deletion-manifest.json)移除了原路径的17个文件，共102473字节。逐项结果及保留内容核验见[删除记录](../../../../outputs/case-acceptance-20261117/deletion-result.json)；未修改ACL或文件属性。删除前后OneDrive客户端均在运行，但Shell未显示同步状态，无法确认云端同步完成；见[删除前](../../../../outputs/case-acceptance-20261117/onedrive-before-delete.json)和[删除后](../../../../outputs/case-acceptance-20261117/onedrive-after-delete.json)记录。归档用于保留历史版本和验收材料，不代表完整RKHMA A3/B3已经迁移。

删除后在项目根目录依次运行`python -B -m unittest discover -s src/test/python`、`mvn -o test`及README中的1000任务场景读取命令。Python 3.13.12的完整测试67项通过；Java 21.0.4的Maven测试为170项，其中169项通过，1项因当前账号缺少创建符号链接权限而跳过。真实场景读取检查通过。日志分别见[Python](../../../../outputs/case-acceptance-20261117/python-tests-after-delete.txt)、[Maven](../../../../outputs/case-acceptance-20261117/maven-tests-after-delete.txt)和[真实场景](../../../../outputs/case-acceptance-20261117/real-scene-after-delete.txt)；原始场景位于`F:\OneDrive\project\static_determine\Scene\1000-10-0-0-12`，运行进程使用Normal优先级。
