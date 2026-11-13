# PR #32 第一版筛查与复核启动失败（2026-11-13）

**第一版仅完成四版筛查，未采用。** 32 个新进程、192 次正确选词保留为历史证据。UI 独立版首次完整 i 两台 VM 的区组差中位数为 +26.185ms、+3.683ms，均为三慢一快，按预登记排除；组合版没有触发筛查排除，但手工审阅发现 Paint 附加字段重置语义不一致，不能采用。独立复核在发键前因测试脚本语法错误退出，实际复核样本为零。修正版见[本次主报告](../index.md)。

本次针对用户要求重新研究原先撤下的改动，先修改实现，再进行隔离对照。基线是 PR #32 已收窄的字体正确性与采样修复版，并非无字体修复的旧默认版。本轮没有重新测试 Fcitx 版，也没有将上次试验的数字当作本轮样本。代码 PR [#32](https://github.com/SandyYuR/fcitx5-android/pull/32) 保持草稿；默认代码与文档分支均未推送或合并。

## 代码审阅与取舍

| 原先涉及的改动 | 本次处理 | 理由与边界 |
| --- | --- | --- |
| 每个 span 段创建 TextPaint | 改为每次遍历一份局部 Paint，每段从原 Paint 恢复后应用样式 | 减少分配，同时保留各类 CharacterStyle 的逐段 updateDrawState 调用；不跨遍历共享可变 Paint |
| 角标关闭时仍创建 TextView | 按首次显示需求创建，隐藏后保留 | 保留默认样式、父容器 LayoutParams、四角定位、颜色与无障碍属性 |
| 每次绑定都写 Flexbox 参数 | 仅在实际 minWidth 或 flexGrow 不同才更新 | 第二次布局仍可改变参数，不以“曾绑定”缓存代替真实状态 |
| 每个按键仅 Native.key 在显示优先级内 | 将 Native.key、统计结果组装、publish 放在同一次优先级范围 | 与已有候选动作一致；日志和转发记账在外，未增加每键优先级切换次数 |
| 移除 TextView 默认样式 | 不恢复 | 构造样式会影响兼容行为，不能凭行数推断性能收益 |
| 只按 MetricAffectingSpan 测量 | 不恢复 | 普通 CharacterStyle 也能改变字体、字号或比例；不缩窄现有契约 |
| 无注释候选／高亮快路径 | 不恢复 | 保留原正文和注释 span 结构，避免把先前的语义改动与本次分配改动混测 |
| 缓存 String 代替每次读取 CharSequence | 不恢复 | 任意可变 CharSequence 的可见内容可能变化，维持原观察行为 |

字体失效修复保持不变。引擎、SDK、方案、模型、Lua、用户词库语义、owner／事件队列与候选代次门控均未更改。优先级改动只覆盖既有单 owner 内的结果发布，没有把整个 sendKey 或转发链路提升优先级。诊断构建才分配时间戳载体，普通构建无此数组分配。

## 精确来源与正确性

| 对象 | 来源提交 |
| --- | --- |
| baseline：已收窄 PR | 2e86e2ecbac1a6d4b28a59fc80b0f81fffdf5b18 |
| ui：Paint、角标、布局 | 47a9a34f724e1b723247f9c8f52f80f29152d935 |
| priority：按键结果发布范围 | 0abc33d64d036c3cbdbe45a3b1f603f1c3f2817e |
| combined：两项组合 | 7f27c8af0ba8fa2f7d4ce113a1122d50d4641f3c |
| 独立驱动 | 8c9c2f836bc2fb5ae691695972115eab49a5b79b |
| Actions | [43362289967](https://github.com/SandyYuR/fcitx5-android/actions/runs/43362289967) |

[源码范围](./source-scope.json)核对 ui 的三份生产文件、priority 的一份生产文件及组合关系。各版独立构建并保存准确提交，不给 APK 改签或更换来源标签；四版共用 CI Debug 签名、SDK 锁和同一性能探针、编辑器、模型。测试 APK 的两项 Paint 独立 oracle 同样复制到未采用复用的基线执行。[产物清单](./products.json)记录所有 APK 哈希；原始含 APK 的构建 ZIP 不提交 Git，另存[构建记录](./build-records.zip)及其[逐文件摘要](./build-records-manifest.json)。

四版目标 JVM 实际执行数为 baseline 79、ui 79、priority 81、combined 81，失败／错误／跳过全零。优先级两版额外验证：由按键结果引起的选项读取仍处于 DISPLAY 优先级，成功和发布异常均恢复 owner 原优先级。见[JVM 与产物核验](./jvm-and-apk-validation.json)。

单独的正确性 VM 在计时前执行每版 15 项、合计 **60 项**，失败／错误／跳过全零，覆盖字体更换、正文／注释渲染、高亮像素、四角角标与尺寸、横向溢出和手势、新增 Paint 复用测量／像素 oracle。[设备原件](./quality.zip)及[核验](./device-quality-validation.json)保留完整来源与结果。新的 oracle 使用显式分段和每段新建 Paint 的独立预期，包含能够改变字体和字号的非 MetricAffectingSpan 样式，检查后续无样式段和基础 Paint 未被污染。

## 预登记与方法

[计划](./reapply-plan.json)在 dispatch 前提交。SDK android-sdk-20261110-1／librime b5561910，锁摘要 6500f364…；万象 v18.1.10 base＋2026-11-07 LTS，沿用全拼、上下文调频、简码、小字集 YAML，fixture 43018095886／13853652309。API 35 AOSP ATD x86_64、KVM、软件图形；100ms 计划 n/i 间隔，显示原配置注释，不采 trace，不注入暖机文字。沿用独立进程 InputManager v3 发键器，全部触摸验证实际保持至少 20ms。

第一阶段是两台新 VM 的四版 Williams 方阵筛查：每台四个区组，每版每区组一次，32 个新进程、192 次正确选词。每版在四个位置各出现一次，12 种有向相邻组合各出现一次。配对单位为同区组的试验版减 baseline，分别报告两台 VM 的四个区组，不能把每次选词当作独立冷启动。

筛查排除规则：若任一冷态主指标在两台 VM 的配对中位数均为正，且每台至少 3/4 区组更慢，则排除该方案。其余按预定顺序 combined → ui → priority 选一个进入独立复核，**不按观察到的最快中位数挑版本**。所有方案被排除则结束，保留负向结论。

复核若执行，则另用两台新 VM、同一份 APK 字节。每台六个区组，恰好三 ABBA、三 BAAB，共 48 个新进程、288 次正确选词。每个区组取两次试验版均值减两次 baseline 均值。筛查和复核不混池，也不替换不利样本。

采用门槛同样事前固定：正确性全过；复核至少一个冷态主指标在两台 VM 均为负中位数且至少 5/6 区组更快；两个冷态主指标均不得在两台 VM 同为正中位数；后五轮任一指标不得在两台 VM 同为正中位数且至少 4/6 区组更慢。这是保守工程门槛，不是正式统计非劣证明；未达标即不自动采用。

## 实测结果

筛查配对区组差，单位 ms；正值表示试验版更慢，每格为中位数〔正／负区组数〕：

| VM | 版本 | 首次完整 i | 首次候选 UP → IC | 后五轮完整 i | 后五轮候选 UP → IC |
| --- | --- | ---: | ---: | ---: | ---: |
| forward | ui | +26.185〔3／1〕 | -0.829〔2／2〕 | +0.153〔3／1〕 | +4.802〔3／1〕 |
| forward | priority | -36.242〔1／3〕 | -0.167〔2／2〕 | +0.145〔3／1〕 | +0.875〔4／0〕 |
| forward | combined | -30.537〔0／4〕 | -1.955〔2／2〕 | +0.176〔4／0〕 | +1.383〔3／1〕 |
| reverse | combined | +0.300〔2／2〕 | -0.106〔2／2〕 | +0.192〔3／1〕 | -0.045〔2／2〕 |
| reverse | priority | +23.744〔4／0〕 | +1.957〔2／2〕 | +0.126〔3／1〕 | +1.047〔3／1〕 |
| reverse | ui | +3.683〔3／1〕 | -2.331〔2／2〕 | +0.075〔4／0〕 | +0.417〔4／0〕 |

合计 32 个新进程、192 次正确选词；这里只合计次数，不合并阶段或 VM 的时延分布。所有触摸实际保持均不少于 20ms。
screen forward 的实际保持范围 20.186～97.011ms。
screen reverse 的实际保持范围 20.177～62.019ms。

## 解释与决定

本轮不把任何试验版并入 PR。ui 的首次完整 i 有同方向偏慢信号；priority 首次完整 i 为 −36.242ms、+23.744ms，方向相反；combined 为 −30.537ms、+0.300ms，不能宣布稳定提速。三种试验版的后五轮完整 i 在两台 VM 中位数都略偏正，完整符号和范围保留，不能当作零回退。

更关键的是正确性复查：Android 15 的 `TextPaint(Paint)` 只复制 Paint 部分，七个 TextPaint 专用字段使用默认值；`set(TextPaint)` 则还复制 bgColor、baselineShift、linkColor、drawableState、density、underlineColor、underlineThickness。本版第一段使用构造复制、后段直接从 View 的 Paint 重置，可能使后段观察到不同的附加字段。原来的 60 项检查没有覆盖这个边界，故通过记录不代表该边界安全。此发现不是上次性能倒退根因的证明；不论本版计时结果如何，都不采用 UI／组合版本。平台[源码快照](./aosp-TextPaint.java.txt)和新版本的加强断言说明这一取舍。

复核失败原因是 workflow 内嵌 Python 的换行转义错误，详见[启动错误记录](./confirmation-setup-error.txt)。没有启动复核模拟器，也没有性能样本可接纳或替换。修正版同时修正生产代码边界与测试脚本，重新登记不同源码的固定计划；本轮筛查不与其复核混池。

前向 UI 版首次 i 实际 UP → 窗口 UP 的区组差中位数为 +41.841ms，实际保持差为 −0.380ms，因此不能把前向偏慢都归给按住时间。反向各阶段不同，不能将阶段中位数相加作因果解释；本轮未采 trace，未证明唯一慢点。

[正向启动附件](./confirm-forward.zip)和[反向启动附件](./confirm-reverse.zip)各只有六个元数据文件，未产生原始进程或采样 JSON。

绝对中位数之差不等于配对区组差中位数，各阶段中位数也不能相加解释全链路。首次 i 指实际注入 DOWN → IME 窗口 UP，包含真实保持时间及主线程等待；候选窗口 UP → IC 指编辑器 InputConnection 入口，尚不是屏幕发光。触摸实际保持、注入迟到及计划／实际时间均另列原值。首次与后五轮进程内中位数分开，后五轮不是五个独立进程。

本轮只测 API 35 x86_64 Debug 模拟器，在卸载重装、恢复夹具且键盘与方案绑定就绪后发键。不能推断真机、ARM、Release、长时间个人词库、绑定未完成的首键、P95 或总体吞吐率。没有 trace，数字变化不能单独锁定引擎、调度或绘制根因。

## 原始证据与归档

[独立复算](./reviewed-results.json)逐项从原始整数纳秒重算全部十二项指标、首次／warm、每区组均值，并验证采集器完整统计、源 APK、顺序、时钟、几何、模型、提交内容与最短保持。复算源码和驱动保存在[分析程序](./analysis-sources.zip)与完整驱动源码；[选择结果](./selection.zip)保留固定顺序选择的原件。

[screen-forward](./screen-forward.zip)、[screen-reverse](./screen-reverse.zip)



[ui 源码](./ui-source.zip)、[补丁](./ui-commits.mbox.txt)；[priority 源码](./priority-source.zip)、[补丁](./priority-commits.mbox.txt)；[combined 源码](./combined-source.zip)、[补丁](./combined-commits.mbox.txt)；[driver 源码](./driver-source.zip)、[补丁](./driver-commits.mbox.txt)。

[来源映射](./source-map.json)登记准确来源及最终 PR 范围。另存四份完整源码、对应 mbox 与[精确 Git 历史包](./source-history-bundles.zip)，历史包以前置提交 2e86e2ec 为基础，包含 priority 的独立旁支和 UI／组合／驱动线性历史；不递归包含子模块。SDK 与预设按锁准备，不把 APK、模型、夹具或密钥提交 Git。

归档先推送后清理本次临时分支及 worktree，恢复性能工作流原禁用状态；用户另建的 refactor/direct-rime-backend-seams 分支保留，历史 CI 保留。见[清理回执](./cleanup-receipt.json)和[全目录摘要](./checksums.sha256.txt)。报告日期采用北京时间，Actions 原始时间保留 UTC。
