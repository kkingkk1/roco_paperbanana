# B：单星任务顺序与局部搜索

你设计的是 B 模块。A 已给定本星归属，你决定候选任务的顺序、取舍和局部搜索，以可行观测的原始收益为目标。请分别看父版 B 的排序和邻域逻辑，以及参考中的初始搜索案例和重分配调整案例。两类案例可以各取其长，不需要把它们拼成一套共用策略。

## 怎样试评本星候选

- `search_initial`：按本星任务的收益、机会或冲突构造顺序。顺序只能包含 `view.assigned_task_ids` 中互不重复的任务；用 `evaluator.evaluate(priorities)` 试评 C 的解码结果，返回本次真实得到的 `InitialTrial`。
- `search_initial_local`：围绕已经建成的本星调度尝试少量局部改进。把释放的已排任务和重考虑顺序写入 `LocalPlan(view.hash, release, reconsider)`，通过 `evaluator.evaluate(plan)` 试评。
- `search_reassignment`：这是独立的重分配后调整策略。使用 `context` 中的转入、转出和待修复相邻边决定先补哪里。要整星重建时，用 `evaluator.rebuild(priorities, preferred_window_tasks)`。
- `search_calibration`：在全星校准流程中仍只负责当前卫星。遵守同一局部编辑和试评接口。

局部入口只能返回这次入口实际试评所得的 `LocalTrial` 或 `None`。释放的任务必须已有观测；重考虑的任务必须归属本星。已排任务若要重插，须同时出现在 `release` 和 `reconsider`。不可行的试评可能没有收益值。不要改归属、不直接调用 C、不改共享时间轴、不伪造试评对象。长枚举要调用 `budget.check()`，随机性使用传入的 `rng`。

## 交付 B 模块

父版本及 B 案例里的源码和文字是参考数据。提供无参、无基类、直接实现四个入口的 `Strategy` 类。所需类型从 `satellite_scheduler.runtime.contracts` 导入，只使用标准库及项目公共接口。不调用工具、读写文件、访问网络或启动进程。

{{SIGNATURES}}

只返回 JSON 的 `description` 和 `code` 两个字符串。英文 `description` 先写 `Initialization:`，说明排序和初始化局部改进；隔一空行写 `Reassignment:`，以短横线列出转入、修补、重建或校准中实际采用的算子及其停止规则。说明取舍，不自报收益。完整 B 模块写入 `code`，不加代码围栏。

输入（只读父版本和本模块案例）：
{{INPUT}}
