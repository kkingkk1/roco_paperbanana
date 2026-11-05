# P1 RKHMA统一接口证据

日期：2026-11-05。工作目录为 `E:\OneDrive\project\static_determine\roco_paperbanana`，主机 `DESKTOP-UKPM2VE`，分支 `master`，基础提交为 `8b380c5aeaa7d6b0ccd06ce9d315ad64ea53cbff`。工作树含此前未提交的项目工作。

运行环境为 Windows、PowerShell 7、OpenJDK 21.0.4、Maven 3.9.11，进程保持 Normal 优先级。真实场景求解使用1核、2 GiB Windows Job总提交内存、读取公共数据后的4.5秒搜索截止与5秒硬截止。对照使用的来源工作树为 `E:\OneDrive\project\static_determine\satellite-basin-hopping`，分支 `main`，HEAD `fda0333454f8273d726e136d7ed67b3207754090`；该工作树有未提交修改，仅只读使用其已有类。

## 实际修改

- A3对所有任务规模使用来源不超过500任务时的复合初始化策略7。初始归属不调用C，也不做初始跨星修补。两个501任务夹具分别区分策略7与策略8，并检查有冲突任务时不会调用初始解码器；前者仅3个任务有窗口，后者501个任务均有合法候选。
- B3在所有规模沿用来源不超过500任务时的初始混合搜索配置。默认对有任务且没有继承状态的卫星执行两次来源种群搜索。移除来源仅在4000及以上任务启用的搜索提前停止、候选预筛、缩小局部搜索范围、延迟全星校准和相关提前停止分支。
- `RkhmaAllocation`、`RkhmaHybridSearch`和`RkhmaGreedyDecoder`直接实现`SolverContracts.A/B/C`。来源协调器路径直接接收这些接口，保留来源原生内核；手工模块可参与组装。RKHMA初始化执行来源单星混合搜索，不额外添加来源中没有的独立初始化局部步骤。手工B原有初始化局部改进未删除。
- A可受控地比较或串联多个完整移动。B可受控地多次调用C。来源重分配保留迁移、替换、交换、任务束以及后续全星校准；窗口偏好经重分配重建接口传递。截止检查点只能保存本次有效试评，非法或伪造试评即使被策略捕获也会使组合失败。
- 通用路径内，A3与B3在同次运行中共享来源协调状态，以保留来源的随机键、重分配反馈和单星搜索状态。初始单星搜索、重分配局部调整及全星校准有各自入口。纯手工组合仍采用受影响星副本和严格收益改进规则。

改前156个方法相关Java文件与测试的副本及哈希保存在 [本轮改前现场](../../../../outputs/p1-rkhma-unified/before/) 和 [改前清单](../../../../outputs/p1-rkhma-unified/baseline.json)。[数据](data.csv)列出本轮比较和正式入口运行；各文件的哈希见[清单](manifest.json)。

## 测试与手工对照

`mvn -o test` 的[最终输出](../../../../outputs/p1-rkhma-unified/full-tests-final-v4.txt)为86项测试、0失败、0错误、0跳过。测试包括19个含RKHMA模块的组合分别通过来源协调器和通用两阶段接口，A/B多候选试评与调用结束失效，跨候选链式试评、伪造检查点、搜索截止恢复、全星校准独立入口、窗口偏好以及大于500任务的初始化边界。501任务夹具在两条运行路径上均观察到每颗有任务卫星的两次初始搜索；4000任务校准夹具仅6个任务有窗口，验证实际改善后仍执行全星校准。另用4000个任务引用的受控测试核对来源星与目标星作为一次完整候选试评，不将该测试当成4000个有效任务的性能测试。

固定时钟、种子42下，三个手工实例共枚举103个完整方案，其中55个可行。8个纯手工组合共24行输出与[改前数据](../../../../outputs/p1-independent-policies/after-manual-final.csv)逐字节相同；[独立oracle](../p1-small-enumeration/independent_oracle.py)检查24行通过。相同只说明这些夹具的结果未变。

## 原版与重组对照

共享场景为 `E:\OneDrive\project\static_determine\Scene\500-5-0-0-24`。均使用全部5星、种子42、来源配置`410000`、固定种群12、标称评价次数120、最多25轮、扰动阈值1和强度0.02。这是受控复现参数，不是默认性能配置。

250任务包含1790个窗口和287117条姿态记录，输入指纹为 `63288eb358817974d40d36424e40112305b7f9fc1bab13a805066f3c388b5630`。来源原版、来源快速路径和通用A3/B3/C3均完整结束，收益1330、250条观测；最终任务归属与观测时间逐项一致。原版初始单星搜索收益为1329，随后历史为 `HoldInitialAssignment`、`MaxWindowLength`、`NoReassignNeeded`，因此此夹具确实进入重分配和全星校准。来源快速路径记录的策略历史与原版一致。通用路径没有输出来源逐代搜索轨迹，不能据此声称其每一步完全相同。

300任务包含2140个窗口和344885条姿态记录，输入指纹为 `52ec585f808b028f2dbf6329ae5907211e4d6104f78576649ded05ed21c6e1ad`。复用[此前已验证的来源原版运行](../../../../outputs/p1-rkhma-phase-orig-300-42-full-v2.txt)。本轮来源快速路径完整结束，收益1618、300条观测；新审计程序输出的输入、初始归属、策略历史、最终归属、收益、观测及状态与该来源记录逐项相同。新程序不输出旧程序的60条逐代轨迹，因此本轮没有重新比较这些轨迹。

同一300任务条件下，通用两阶段路径在搜索截止时返回收益1615、297条观测的检查点；该输出经[独立重建验证](../p1-rkhma-phase-comparison/VerifyComparison.java)通过。来源路径完整结束，通用路径未完整结束，因此不能将二者差异归因为算法本身，也不能把该次检查点称为完整RKHMA终点。数据中的250/300耗时来自资源脚本的就绪后墙钟；500任务耗时来自正式入口输出，两者计时边界不同。

共同审计程序在就绪信号前单独执行一次来源策略7，用于打印可核对的初始归属；正式求解仍在就绪信号后自行执行并计入预算。独立的A3测试检查其结果与策略7一致。原版和当前代码的结果均通过问题层验证；源码和数据身份见[清单](manifest.json)。

## 正式入口和真实场景

README场景检查得到1000任务、10星、2823窗口、444817条姿态记录，空方案可行、收益0。500任务、5星、种子42的正式入口中：

- `A3B3C3`：收益2400，392条观测，`SEARCH_EXPIRED`。
- `A3B1C3`：收益2401，387条观测，`SEARCH_EXPIRED`。
- `A1B3C1`：收益2110，387条观测，`SEARCH_EXPIRED`。

三个输出分别由 [P1SolverCheck.java](../p1-two-stage-runtime/P1SolverCheck.java) 独立重建，均返回 `feasible=true` 且收益与输出一致。这些是截止检查点，不是完整运行性能比较。

## 重放命令

命令在项目根目录执行。脚本要求输出文件不存在；重放时把示例输出改为 `outputs/` 下的新文件。

```powershell
mvn -o test
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' 1000 10

java -cp 'target/classes;target/test-classes' method.runtime.P1EnumerationAudit `
  'outputs/<新的手工对照>.csv'
python -B 'vault/Results/Evidence/p1-small-enumeration/independent_oracle.py' `
  'outputs/<新的手工对照>.csv'

New-Item -ItemType Directory -Force 'target/p1-rkhma-phase-check/classes', `
  'target/p1-rkhma-unified/classes' | Out-Null
javac --release 21 -encoding UTF-8 -cp target/classes `
  -d 'target/p1-rkhma-phase-check/classes' `
  'vault/Results/Evidence/p1-rkhma-phase-comparison/ComparisonFixture.java'
javac --release 21 -encoding UTF-8 `
  -cp 'target/p1-rkhma-phase-check/classes;target/classes' `
  -d 'target/p1-rkhma-unified/classes' `
  'vault/Results/Evidence/p1-rkhma-unified/UnifiedRkhmaAudit.java'
pwsh -NoProfile -File 'vault/Results/Evidence/p1-rkhma-unified/RunUnifiedComparison.ps1' `
  -Implementation generic -Tasks 250 -Seed 42 -ReassignIterations 25 `
  -Deterministic default -SatellitePool 1 -Output 'outputs/<新的通用对照>.txt'
```

将 `-Implementation` 改为 `native` 可运行来源快速路径。原版重放使用[原版对照脚本](../p1-rkhma-phase-comparison/RunComparison.ps1)的 `-Implementation original -OriginalMode untimed -Budget fixed`，并需来源类和已缓存的 Commons Math；项目生产代码未新增依赖。旧版 `AssembledRkhmaAudit.java` 固定了本轮之前的专用接口，历史数据和文件保留；当前版本使用本证据的 [UnifiedRkhmaAudit.java](UnifiedRkhmaAudit.java)。

## 失败与修正

最初的共同运行脚本误把`fixed`传为实现名称，在就绪信号前以 `unknown mode` 退出；已改为传入`native`或`generic`并完成重放。窗口偏好测试最初将原始窗口首记录误当可用起点，另一个夹具的目标星没有初始状态而未触发来源快速修补；现分别按窗口身份检查，并让目标星先有初始任务，修正后测试通过。

首次300任务通用运行暴露来源内部尝试无合法候选的目标归属，运行时因此报告 `move owner has no legal candidate`。A3现将该内部候选作为不可行候选跳过；原有对外非法输出门禁保留。修正后300任务限时运行可返回独立验证通过的检查点。一次PowerShell搜索误用了Bash花括号展开，解析阶段失败；随后改用目录范围内的`rg`，未修改文件。

## 边界

500以上任务采用策略7是本项目明确修改，不能声称复现来源原来的策略8及初始跨星修补。来源算法的小实例自动种群可低于DE/rand/1要求；项目设最低6，见[既有分阶段证据](../p1-rkhma-phase-comparison/evidence.md)。非1秒内部间隔采用本项目合法候选适配，不承诺复现来源窗口排序或完整轨迹。上述单场景、单种子结果是工程验收，不构成方法性能或机制收益结论。
