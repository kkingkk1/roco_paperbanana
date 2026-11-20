# P2 单实例实评与选版证据

2026-11-20完成P2任务2.3的单实例工程检查。输入是P1的 `half_second` 夹具，含4个任务和2颗卫星。按原始场景格式导出后回读核对了任务、窗口和姿态字段。运行一代、一链、两轮，种子固定为42，初始化选择 `A2B1C1`。 [夹具源码](../../../../src/test/python/test_runtime.py#L26) · [P1枚举证据](../p1-small-enumeration/evidence.md)

## 结果

- 初始化实评1次。第1轮新增7次并引用1个既有评价事件。第2轮引用第1轮和初始化的8个评价事件。实评共8次，16个轮内搭配槽位中有9个缓存命中。演化模型调用0次。
- 第1轮选出 `A1B1C1`，收益从18升至24，归一化成绩从0.75升至1。第2轮将它用作父版。代末对初始化和两轮的有效组合去重后仍选出 `A1B1C1`，它在同收益组合中记录耗时最短。
- 8次调度均通过进程外终验。独立枚举核对了每项观测和收益。8次计时区间累计0.201秒，公共数据读取与进程启动另计。
- 相同运行ID重跑新增求解0次。将求解种子改为43并沿用运行ID时，入口拒绝复用。项目Python测试79项通过。

本证据覆盖预置组合在一个场景中的流程。两轮处理同一批完整组合，可检查父版变更和原评价事件复用。完整演化中的多实例汇总及不同轮次候选仍待核对。 [P2计划](../../../Plan/mechanisms/P2-五角色协作演化.md)

## 命令与记录

从项目根目录用Windows PowerShell 7和Python 3.13.12执行。求解进程保持Normal优先级，使用单核、2 GiB和5秒硬截止。

```powershell
$env:PYTHONPATH = 'src/main/python'
$runId = 'p2-task2-3-single-instance-20261120'
python -B -m satellite_scheduler.evolution preset-run $runId `
  "outputs/$runId/half_second" 4 2 --seed 42 --parent A2B1C1
python -B -m unittest discover -s src/test/python
```

- [运行条件与版本](../../../../outputs/p2-task2-3-single-instance-20261120/preset-evolution/run.json)
- [第一轮](../../../../outputs/p2-task2-3-single-instance-20261120/preset-evolution/rounds/1.json)及[第二轮](../../../../outputs/p2-task2-3-single-instance-20261120/preset-evolution/rounds/2.json)
- [代末选版](../../../../outputs/p2-task2-3-single-instance-20261120/preset-evolution/selection.json)
- [实评数据](data.csv)及[核验清单](manifest.json)
