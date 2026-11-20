# P2 50任务、2星场景实评证据

2026-11-20按指定生成器的区域分布生成一个50任务、2星场景。任务生成沿用原 `.NET Random` 的抽样顺序，显式生成种子为 `20261120`。使用原第1、2颗卫星的全天星历和 `CC4_1` 计算观测窗口，时段为2013-04-20的00:00至次日00:00。以项目加载器回读两颗卫星的71、70个窗口。 [生成器来源](../../../Knowledge/Source-Provenance.md#本轮实验数据的指定生成器) · [场景清单](../../../../outputs/p2-task2-3-50x2-20261120/scene-manifest.json)

## 结果

- 从 `A2B1C1` 出发，按求解种子 `42` 跑一代、一链、两轮。每轮8种搭配，合计8次实评和9次缓存命中。第二轮引用全部原评价事件。演化模型调用0次。
- 初始收益284。第1轮选出 `A1B2C2`，收益294，安排45个任务。归一化成绩约0.9899。第2轮继承这一父版，代末仍选它。
- 回读第2轮时，A单改和C单改的收益分别降至290和289。三个双改和一个三改均有评价事件引用并参与排序。代末合并初始父版和两轮记录，去重后从8个有效版本选第1名。
- 8次均在搜索截止时返回可行调度，通过5秒硬截止内的进程外终验。重新加载场景检查了全部调度、收益、父版继承及排名。同运行ID回读新增求解0次。
- 场景生成用时38.643秒。8次实评计时合计36.230秒，含终验，进程启动和公共数据读取另计。

本次检查覆盖单一场景和8种预置组合。 [P2任务计划](../../../Plan/mechanisms/P2-五角色协作演化.md)

## 实际命令与记录

工作目录为项目根目录。Python 3.13.12在Windows上运行。求解沿用单核、2 GiB进程组内存上限和Normal优先级。

```powershell
$env:PYTHONPATH = 'src/main/python'
$runId = 'p2-task2-3-50x2-20261120'
$runDir = "outputs/$runId"
python -B -u "$runDir/generate_scene.py" 2>&1 | Tee-Object -LiteralPath "$runDir/generation.log"
python -B -m satellite_scheduler.evolution preset-run $runId `
  "$runDir/50-2-0-0-24" 50 2 --seed 42 --parent A2B1C1
```

- [生成脚本](../../../../outputs/p2-task2-3-50x2-20261120/generate_scene.py)及[生成日志](../../../../outputs/p2-task2-3-50x2-20261120/generation.log)
- [运行条件](../../../../outputs/p2-task2-3-50x2-20261120/preset-evolution/run.json)及[代末结果](../../../../outputs/p2-task2-3-50x2-20261120/preset-evolution/selection.json)
- [第一轮](../../../../outputs/p2-task2-3-50x2-20261120/preset-evolution/rounds/1.json)及[第二轮](../../../../outputs/p2-task2-3-50x2-20261120/preset-evolution/rounds/2.json)
- [实评数据](data.csv)及[核验清单](manifest.json)
