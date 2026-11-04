# 问题模板初始化证据

更新日期：2026-10-06。

## 核查问题

模板是否只保留问题建模，并具备标准研究知识库入口与可追溯的初始化记录。

## 初始问题层验证

以下结果来自本聊天此前实际完成的验证。
本轮只补齐知识库，没有重跑未受影响的 Java 测试。

工作目录：`E:\OneDrive\project\static_determine\new_hybrid_problem_template`。
Java 21，Maven 3.9.11。
构建使用本机缓存，没有下载或安装依赖。

```powershell
mvn -o test
java -Xmx2g -cp target/classes main.InspectScene `
  'E:\OneDrive\project\static_determine\Scene\1000-10-0-0-12' `
  1000 10
```

测试结果：16 项通过，0 失败，0 错误，0 跳过。
测试分为 ScheduleValidatorTest 的 12 项与 DataLoaderTest 的 4 项。

真实场景：1000 个任务、10 颗卫星、2823 个窗口、444817 条姿态记录。
其中 2 个窗口结束早于开始。
模板保留这类原始窗口，任何不满足完整窗内成像的观测均非法。

独立 SceneFingerprint 程序分别在来源加载器与模板加载器的隔离类路径下运行。
它比较任务字段、任务到窗口的映射、卫星窗口、姿态记录及输入顺序。
两侧 SHA-256 相同：

`b624266760b5b21426b015d4ff2b91babadf78ee4d7a1f6fee123da28d9dcc0b`。

该指纹标识已解析的数据值及顺序，不冒充原始数据文件的哈希。

真实数据显式观测：task=0，satellite=0，start=38840，end=38863。
评价结果为 feasible=true、totalProfit=5。
同一观测终点提前一秒后，返回 feasible=false 和 IMAGING_DURATION。
上述独立程序保留在本聊天工作目录的 work 中，不进入模板代码。

## 本轮知识库验证

- 检查三份顶层入口、Gate 计划、报告和证据文件均存在。
- 检查所有本地文档链接与公式美元符配对。
- 检查状态页、进度页、Gate 与报告均记录同一初始化状态。
- 与本轮修改前快照比较，既有源码、测试和构建配置哈希不变。
- 检查新增内容未指定算法、seed、性能预算或默认求解方法。
- 检查交付压缩包逐文件内容与新项目一致。

## 结果与边界

问题层与知识库初始化验收通过。
没有新增方法实验，也没有算法性能结论。
真实数据验证范围限定为上述一个场景。
原项目未由本轮写入；此前观察到的 Codex 内部 Git 快照生命周期变化不作为工作树改动。

## 文件说明

- [data.csv](data.csv)：初始化检查的实际结果摘要，标明本轮检查与复用的既有验证。
- [manifest.json](manifest.json)：来源、命令、数据指纹、环境和文档哈希。
- [报告](../../Reports/problem-template-initialization.md)：给人读的结论与限制。
- [来源记录](../../../Knowledge/Source-Provenance.md)：选择性迁移范围。
