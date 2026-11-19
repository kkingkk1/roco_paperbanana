# Python真实检索联调证据

## 2026-11-17 真实联调

2026-11-17使用Python检索入口和已有Codex CLI，完成一次真实模型请求。结论见[联调报告](../../Reports/p2-retriever-python.md)。候选由人工比较实际决策规则后选择：A-001、A-002、A-010、A-017；BI-001、BI-003、BR-001、BR-005；C-001、C-002、C-003、C-066。其余案例当时尚未完成语义复核，不计入这次候选。

候选数分别为A 4、B 4、C 4，目标返回数为A 1、B 2、C 1，B两个阶段各至少1条。实际选中的编号、版本和B阶段见[data.csv](data.csv)。请求只包含候选编号、类别、描述和B阶段；完整候选代码及后台来源仅保存在本地运行现场。程序按当前版本关联来源，解析实际原始回复，核对数量、模块、编号、阶段及非空适配说明，再冻结所选源码和说明。

模型标识为 `gpt-6.1-terra`，调用时设置 `model_reasoning_effort=low`。实际事件记录为1次完成的模型轮次、34155输入tokens、245输出tokens、0推理输出tokens。CLI没有提供不可变模型快照，不能把模型别名当成已冻结快照；详见[manifest](manifest.json)。

运行现场为 `outputs/python-retriever-20261117-134111/`：

- [实际请求](../../../../outputs/python-retriever-20261117-134111/request.txt)、[原始回复](../../../../outputs/python-retriever-20261117-134111/response.json)和[模型事件与用量](../../../../outputs/python-retriever-20261117-134111/codex-events.jsonl)。
- [冻结案例包](../../../../outputs/python-retriever-20261117-134111/case-package.json)、[候选版本](../../../../outputs/python-retriever-20261117-134111/candidates.json)及[后台来源快照](../../../../outputs/python-retriever-20261117-134111/candidate-sources.json)。
- [运行记录](../../../../outputs/python-retriever-20261117-134111/run.json)保存包与回复哈希。包哈希计算精确的 `case-package.json` UTF-8字节，`run.json`不参与计算。

按相同 `run_id` 恢复时，程序复用原候选、回复和冻结包，核对模型事件及哈希，未发生新的模型轮次。模型回复只作适配建议；本次人工核对其所选B案例阶段及主要规则，未将自然语言建议视为性能结论。传输失败后的重试方式尚未确定；全库语义去重、不可变模型快照和五角色接入尚未完成，这次联调不是先导固定包或完整P2验收。

这次真实调用早于语义复核版本门禁。随后将这12条的版本和决策区别整理进[复核记录](../../../../cases/backend/semantic-review.json)。新的本地准备检查在[运行现场](../../../../outputs/python-semantic-review-20261117-141318-201635/prepared.json)成功冻结该记录，未调用模型；未列入复核组的 `C-004` 被拒绝。门禁启用后再次恢复本次真实调用，模型事件文件未变化。哈希只绑定复核版本，不证明其余案例语义不同。

## 2026-11-19 任务1独立验收

复用上述真实请求的候选、模型事件、原始回复和冻结包。12条所用案例与当前版本内容逐项一致；重新解析选中ID及适配说明，恢复结果与原包一致。运行前后原现场的文件名、字节哈希和修改时间相同，本次新增模型调用为0。

使用当前案例库逐条核对149个版本及后台来源。本地构造覆盖全部候选的请求，配额为A 2、B 2、C 4，提示不包含完整源码或后台来源。该149条请求未发给模型。候选计数边界、错误归属、重复、B阶段缺失、无效JSON、空适配说明和缺失文件均按合同处理。已有案例与Retriever测试11项通过，覆盖B配额、错目录、源码哈希等边界。

在项目根 `F:/OneDrive/project/static_determine/roco_paperbanana` 使用PowerShell 7.6.10、Python 3.13.12，以Normal优先级启动。输入为本项目`cases/`及上节真实运行，规则见[案例与生成协议](../../../Knowledge/多智能体算法与评价合同.md#13-案例与生成协议)。

```powershell
$env:PYTHONPATH = 'src/main/python'
python -B 'outputs/p2-task1-acceptance-20261119/verify_retriever.py' 2>&1 |
  Tee-Object -FilePath 'outputs/p2-task1-acceptance-20261119/verification.log'
$env:PYTHONPATH = 'src/main/python;src/test/python'
python -B -m unittest -v test_cases test_retriever 2>&1 |
  Tee-Object -FilePath 'outputs/p2-task1-acceptance-20261119/tests.log'
```

见[核对脚本](../../../../outputs/p2-task1-acceptance-20261119/verify_retriever.py)、[核对日志](../../../../outputs/p2-task1-acceptance-20261119/verification.log)和[测试日志](../../../../outputs/p2-task1-acceptance-20261119/tests.log)。本次支持案例与检索协议的独立交接。五角色所用的固定模型快照、调用接口和先导用案例包由[P2任务5](../../../Plan/mechanisms/P2-五角色协作演化.md#任务5小预算真实角色验证-)验收。
