# Foxy 审查后的响应性修复与同条件验证

适用分支：`feat/direct-rime-prototype`。产品修复提交：`8ef7c6c6`。
本轮只验证修复后的直连版与用户提供的 Foxy 原 APK，不重新测试旧 Fcitx 或修复前版本。

## 两份新增真机日志说明了什么

两份日志均来自 `9d5c7992` Debug，设备为 OnePlus PJD110 / Android 16，
进程号均为 9414。日志中没有进程创建，不能把 `ColdStart` 标签解释为进程冷启动。
原始日志不上传仓库；脱敏后的时间记录见本报告配套数据。

| 场景 | 原生操作及发布 | 其他阶段 | 可以得出的结论 |
| --- | --- | --- | --- |
| 20:29，generation 1515 首次点选 | 123.519ms | owner 等待 0.434ms；提交服务处理 3.604ms | 大头在原生调用及快照/发布区间 |
| 20:29，generation 1521 首次点选 | 135.267ms | owner 等待 0.305ms；事件等待 1.341ms | 不是事件队列积压造成全部延迟 |
| 20:31，generation 1576 第二次点选并上屏 | 150.415ms | owner 等待 0.173ms；提交服务处理 2.670ms | 第一次局部选词只有 10.102ms，不能与最终提交混为一谈 |
| 非输入框绑定 | 594–756ms 的会话创建 | 多次 `needs_rime=false` 之后仍 `created=1` | 无效绑定破坏了原来可复用的真实编辑器会话 |

旧 `native_publish` 同时覆盖原生动作、快照读取及 Kotlin 发布，不等于纯 librime 时间。
20:31 日志还记录了展开候选一次请求 144 项，会占用唯一的原生 owner 线程。
万象 `context_reorder.lua` 会在首次需要时打开 LevelDB；仅凭现有日志无法把
123–150ms 精确归因于它，也没有依据修改用户方案、禁用学习或预先模拟输入。

## 已完成的产品修改

1. **非输入框不再仅因窗口显示或 focus 创建 Rime 会话。** 实际软键到达时先提升
   会话需求、排入绑定，再接收该键的 generation ticket。硬键路径保持同样的顺序。
   主动切换中英文或方案仍可以请求会话。同应用热会话可跨过非输入框页面保留；
   不同真实编辑器应用、密码及敏感环境的隔离规则继续生效。
2. **展开候选按最多 8 项拆分原生任务。** 逻辑分页仍可返回完整一页，任务之间允许
   按键排入 owner。取消加载会停止后续批次；候选版本改变立即取消旧页，避免拼接
   两个输入状态。版本按候选实际失效推进，不用会被无效 key-up 推进的快照 revision。
3. **减少 JNI 重复查找及临时分配。** 在 `JNI_OnLoad` 缓存 Snapshot 构造器与 String
   类引用，并在卸载时释放；候选列表预留容量、每次只取一次选择键。
4. **恢复 Release 的 ART profile 管线。** 启用 ProfileInstaller，加入应用/输入服务、
   直连后端、Snapshot 与候选更新入口的规则，保留 R8/minify。
   已核验最终 APK 内二进制 profile 的 DEX CRC 与方法编号，不能只凭文本规则宣称生效。
   当前包包含 1,310 个 profile 方法，其中 57 个属于应用；这些数量不代表性能提升幅度。
5. **细分原生诊断。** 区分 core、读取提交、context、status 和 input tabs；绑定另记
   native begin 与状态恢复。只在首几个操作或慢操作输出计时，不记录输入内容。

没有增加跨应用会话池，没有禁用万象上下文调频，也没有加入伪造输入的预热。

## 验证与产物

- 484 项 JVM 测试通过。候选分页新增覆盖取消、版本过期和按键能插入批次之间的测试。
- 原生契约及 sanitizer 检查通过；Android 完整前端触摸/引擎验证通过。
- [产品修复 CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39128700863)
  与 [JVM CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39128700803)。
- [ARM64 Release 构建与比较任务](https://github.com/SandyYuR/fcitx5-android/actions/runs/39130000027)，
  产品源码 `57e4b9cd1bb279a0ceaa2677a99bedfd1d69ac4a`；相对 `8ef7c6c6` 只新增测试工具。
- APK SHA256：`1b3a5632ebca3900b7cad51166b079cc54e68b98604bab43ef7e5faae00fdcbc`。
  使用仓库 Release 签名，证书 SHA256：
  `ba43006e1bfa3f60f46b2a9056f593a3a61445bd2571319fb1869e49cc36b1fe`。
- 包内只有 `libdirect-rime.so` 与 `libc++_shared.so`，未发现旧原生 Fcitx 后端或依赖；
  ELF 16KiB 对齐验证通过。

## Foxy 对比方法与限制

Foxy 是上传的 `0.0.1-5458f3c` ARM64 Release，SHA256
`c81783716626c76469a20f1884abfeed63f05349a55ca052ee44079a01b7b80c`，未改包或注入 IME 测试代码。
经用户明确授权，将原 APK 放入独立的临时测试分支供 Actions 读取，完成后删除该分支。
Foxy 原生 librime 为 1.17.0，与本项目的固定 librime 提交并不相同。

两边均安装同一份万象 base v18.1.3 + 固定 LTS 模型，使用上传的两份 YAML；
仅补入用户确认开启的 `context_reorder: true`。保持全拼、简码、小字集与其他用户选项。
通过实际中文提交和进程映射验证引擎及 `.gram` 模型加载。

环境为 Android 30 Google APIs x86_64 模拟器。两款 APK **均为 ARM64**，
使用同一 `libndk_translation.so`；这是相同翻译条件下的功能/响应观察，不是手机性能证明。
中立编辑器在自身进程注入真实触摸，记录注入前时间、`InputConnection.commitText` 入口、
TextWatcher 与 CPU `onDraw`，不把 `onDraw` 当成最终显示帧。
候选点选前确认实际出现 `ni` 编码，检验每次只提交一个“你”，保留失败记录。
预备部署与截图校准不算性能样本。首次校准因短服务名识别错误选中了系统键盘，已废弃并修正，
不能用该失败推断 Foxy 的输入能力或速度。

测试包含新进程首次输入，以及跳到系统设置这个非输入框应用后返回编辑器的首次输入。
文件页缓存、已部署方案与学习数据库保留，因此“新进程”不等于“全新存储冷缓存”。
最终逐次结果与统计随比较任务结束归档，不用早期小样本宣称本轮修复已消除真机卡顿。
