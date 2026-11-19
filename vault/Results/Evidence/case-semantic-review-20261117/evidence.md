# 现有案例决策逻辑复核证据

本次复核覆盖2026-11-17项目内149条当前Python案例：A 29条、B初始化30条、B重分配后调整10条、C 80条。各案例的主要决策及对应元数据版本保存在[语义复核记录](../../../../cases/backend/semantic-review.json)；[数据表](data.csv)和[manifest](manifest.json)记录检查范围。项目目录为`F:\OneDrive\project\static_determine\roco_paperbanana`，问题表示及合法性检查遵循[问题建模](../../../Knowledge/问题建模.md)。

按模块及B阶段比较源码中的选择目标、邻域或分支、接受与终止规则。A案例包括随机或基于窗口、负载、收益的分配，以及竞价、匹配、单纯形和分支定界等不同求解过程；B案例区分排序、局部邻域和接受规则，两个阶段分别核对；C案例区分构造、局部编辑和重建中的观测选择。各组代码中未确认仅改名、语言、随机种子或数值参数的重复案例，因此本次未合并编号。若参数选择改变实际评分目标或搜索规则，依据[入库规则](../../../Knowledge/案例入库与接口适配.md#入库与去重)分别保留。

对C的80条案例，复用[先前72组同输入对照](../case-acceptance-20261117/evidence.md)中的每条构造和局部编辑输出，检查3160对案例。原有结果区分3149对；其余11对由[原有边界反例](../case-acceptance-20261117/data.csv)、既有随机场景及[本次补充场景](../../../../outputs/case-semantic-review-20261117/c-counterexamples-wide.json)区分。可复核的配对汇总见[C配对结果](../../../../outputs/case-semantic-review-20261117/c-pairwise-review.json)。例如，C-001按最早完成选择，C-026最大化窗口两侧的最小余量；C-065接受首个缩短跨度的重定时，C-079选择整轮中跨度缩短最多的候选。

对B初始化的30条案例，12个既有夹具及四种评价次数上限区分435对中的431对。原夹具使用固定的合成顺序评分，不代表实际卫星调度收益。剩余4对使用项目C-001解码器和`problem.evaluate`，在构造的冲突场景中得到不同顺序及可行调度；见[真实解码反例](../../../../outputs/case-semantic-review-20261117/b-real-decoder-witness.json)和[B配对结果](../../../../outputs/case-semantic-review-20261117/b-initial-pairwise-review.json)。例如BI-003接受首个改进交换，BI-007继续搜索插入与反转邻域。B重分配后10条和A的29条按源码决策及既有对照检查；本次未声称对它们完成全部案例对的行为反例。

本次结果是针对所列版本的代码复核和有限输入检查，不是所有可能输入上的形式化证明。不同输出不能单独证明两条案例属于不同算法，哈希只绑定所审查的文件。新增或修改案例后，须重新比较相关决策并更新复核记录；现有版本仍有接口适配和方法覆盖边界，不能仅凭复核记录判断收益或完成整个P1/P2验收。

运行时使用Python 3.13.12及Normal优先级。在项目根目录执行：

```powershell
$env:PYTHONPATH = 'src/main/python'
python -B outputs/case-semantic-review-20261117/c_counterexamples_remaining.py --seeds 1000 --wide
python -B outputs/case-semantic-review-20261117/verify_c_pairwise.py
python -B outputs/case-semantic-review-20261117/b_real_decoder_witness.py --seeds 100 --max-evaluations 40
python -B outputs/case-semantic-review-20261117/verify_b_pairwise.py
```

C反例脚本实际检查1个随机种子；B真实解码脚本实际检查58个种子。这些种子和评价次数只用于本次检查，不是项目的实验配置。

Python完整测试67项通过，见[测试输出](../../../../outputs/case-semantic-review-20261117/python-tests.txt)。本地读取全部149条当前案例并核对唯一来源版本；全部提供为候选时，按现行配额应选择A 2条、B 2条、C 4条。请求描述不含后台`source`或全部候选源码。本次未发起新的模型调用；先前真实检索只使用12条复核候选，见[联调证据](../p2-retriever-python/evidence.md)。
