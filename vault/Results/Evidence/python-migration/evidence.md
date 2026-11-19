# Python迁移阶段证据

本页记录初次迁移和RKHMA补验，覆盖P1预置求解器的重新验收。结论及边界见[阶段报告](../../Reports/python-migration.md)。

## 2026-11-18：RKHMA A3/B3 补验

统一Python入口支持27种组合。对三个小实例新增45组Java同输入对照，覆盖含A3或B3的15种新组合。固定时钟、种子42下，45组的收益、归属、观测任务/卫星/起止时刻、试评移动数和接受移动数均一致；配合之前的24组手工和12组C3对照，覆盖全部27种组合。基准由[Java对照程序](../../../../outputs/rkhma-python-20261118/PythonRkhmaParity.java)运行保留的Java实现生成；[对照表](../../../../src/test/python/fixtures/rkhma-combinations-java-baseline.csv)由[Python回归测试](../../../../src/test/python/test_rkhma_runtime.py)逐项读取。

A3初始归属与此前Java的12个策略7样本一致。种子1234、固定种群6和评价次数30的强制同星归属夹具实际接受跨星迁移，最终归属、观测和收益32与来源Java一致。另一夹具检查全星校准包含未参与迁移的第三颗卫星，并通过可控时钟验证初始化和全星校准截止时保留可行解。

真实场景来自只读共享目录 `F:\OneDrive\project\static_determine\Scene`。两种实现用种子42、各自正式 `A3B3C3` 入口，均设置单核、2 GiB进程组内存和5秒硬截止，进程优先级为Normal。四次输出均通过各自求解进程外的验证，且均为 `SUCCESS / SEARCH_EXPIRED`。两个场景分别逐条比较任务、卫星、原始窗口序号和起止时刻，结果完全一致。

| 场景任务数 | 收益 | 观测数 | 耗时（Python / Java，毫秒） |
|---|---:|---:|---:|
| 500 | 2400 | 392 | 4646.855 / 4708.339 |
| 1000 | 2032 | 292 | 4624.683 / 4692.047 |

原始输出：500任务 [Python](../../../../outputs/rkhma-python-20261118/500-5-0-0-24-python-A3B3C3-final.log)、[Java](../../../../outputs/rkhma-python-20261118/500-5-0-0-24-java-A3B3C3.log)；1000任务 [Python](../../../../outputs/rkhma-python-20261118/1000-10-0-0-12-python-A3B3C3-final.log)、[Java](../../../../outputs/rkhma-python-20261118/1000-10-0-0-12-java-A3B3C3.log)。真实场景只对照截止返回的可行方案，不用于判定完整搜索轨迹或比较算法性能。生成代码的隔离执行与独立终验仍未覆盖。

在 `F:\OneDrive\project\static_determine\roco_paperbanana` 用PowerShell 7.6.10执行。Python 3.13.12的[完整套件](../../../../outputs/rkhma-python-20261118/python-tests-final.log)73项通过，包括C越权移除观测时组合失败；Java 21.0.4、Maven 3.9.11的RKHMA对照套件17项通过。命令如下；1000任务时将 `$scene` 最后一段改为 `1000-10-0-0-12`，并把任务和卫星数分别改为1000、10。

```powershell
$env:PYTHONPATH = 'src/main/python'
rtk proxy python -B -m unittest discover -s src/test/python
rtk proxy mvn -o -Dtest=RkhmaRuntimeTest test
rtk proxy java --class-path 'target/classes;target/test-classes' `
  'outputs/rkhma-python-20261118/PythonRkhmaParity.java'

$scene = 'F:\OneDrive\project\static_determine\Scene\500-5-0-0-24'
rtk proxy pwsh -NoProfile -File '.\scripts\run-python-manual.ps1' `
  -Scene $scene -Tasks 500 -Satellites 5 -Combination A3B3C3 -Seed 42
rtk proxy pwsh -NoProfile -File '.\scripts\run-manual.ps1' `
  -Scene $scene -Tasks 500 -Satellites 5 -Combination A3B3C3 -Seed 42
```

## 2026-11-17：问题层、案例与部分运行时

环境、数据路径、资源上限和指纹见[manifest](manifest.json)；真实场景输出见[data.csv](data.csv)。实际命令、原始输出、Java独立复核程序及范围见[运行记录](../../../../outputs/python-migration-20261117-112030-101b2e/migration-verification.md)。

1000任务、10卫星场景中，Python与Java加载的输入及377674个有序观测候选的指纹一致。新增Python手工入口在500和1000任务场景的5秒内由独立进程验证调度，进程组为单核、2 GiB且优先级为Normal。三次输出在运行结束后又通过Java验证器复核；这次Java复核不计入5秒。另人为暂停本次新建的求解子进程，确认硬超时被报告且两个进程均退出。早期三次输出及其复核仍保留在表中。

Python测试57项通过；保留的Java测试170项中169项通过、1项跳过。跳过项需要创建符号链接，当前Windows账号缺少所需权限。RKHMA C3在12组场景的构建、局部编辑及重建对照一致，并在三个小实例与手工A/B组合对照。149条当前案例清单均指向Python，当前版本与直接历史父版本均可读取；完整迁移前后记录见[案例清单](../../../../outputs/python-migration-20261117-112030-101b2e/case-migration-review.md)。

本批实验当时未涉及A3/B3、生成代码隔离和五角色控制程序。[当时的语义复核记录](../../../../outputs/python-migration-20261117-112030-101b2e/case-semantic-review.md)仅提供线索；后续的实际决策逻辑比较见[149条案例的复核报告](../../Reports/case-semantic-review-20261117.md)。
