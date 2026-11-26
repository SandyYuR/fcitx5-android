# 2026-11-26 EVO 候选排序与两类重置

本轮让已加载的 librime-evo 按当前方案声明，在候选长按菜单中提供“前移一位”“后移一位”“重置手动排序”和“重置自动调频”。各项独立显示，缺少或无法解析的绑定只隐藏对应动作；保留原万象 `super_sequence` 路径。

## 来源与交付状态

| 对象 | 固定基线或实测值 |
| --- | --- |
| 代码提交 | [`2cd8d0f55046479206ec3adddb39426223d3bc0c`](https://github.com/SandyYuR/fcitx5-android/commit/2cd8d0f55046479206ec3adddb39426223d3bc0c)，已提交至本地 `fx-rime-only`，未推送 |
| 文档基线 | `0cf64dfff5eb87f608f7b34f7018140c19841a23`；本记录随本地 `docs` 提交，以包含本记录的提交为文档版本；本轮未推送 |
| 设备 | 唯一本机 AVD `rime`，`emulator-5574`，API 35、x86_64 |
| EVO provider | `org.rime.plugin.sandyyur.librime_evo`，安装版本 `1.17.0-evo-9d27c24b` |
| 实测 EVO DSO | 源码 `9d27c24baad02f33384949eced84d30e1690dad3`，SHA-256 `977167f8f977b1230aed7c57b28545d8830346029e09a26d07922d5850e3e2f8` |
| 最终 x86_64 Debug APK | `org.fcitx.fcitx5.android.fx.rime-nightly-0.1.3-884-gceecdf15-20261126-081630-0-gceecdf158-fx-x86_64-debug.apk`；SHA-256 `ca5e76ccdecddf00ec87f74ad0b64f0e8935008d40d2d18c6191b6a1d01692b2`；沿用原包名和签名，以 `adb install -r` 安装 |
| AndroidTest APK | SHA-256 `7aeb80e0098c9f761cf07e8d35cd4360d3012fd3e1b7f7e3ad39113144bc6e2a` |
| APK 内 bridge | Build ID `abb95848aafbdab610f68606ba01380ceb4619aa`；SHA-256 `2d5e4a284570af85e425216ecf3e2204fd0153d6ef1c553d43fec67759f24271` |

早期设备测试包 SHA-256 为 `5e9a6277fd63fadad3da83c03c443167537c6dc1d6662dfafb556cfac80e2ef2`；标签版中间 APK SHA-256 为 `ee64b7a59b24a57d29c59a96cfae3c62b2b015cd182af86cd3f077f2a1e04766`。经本次绑定安全修正后，最终实际安装与回归测试的 APK 为上表所列 `ca5e76...692b2`，其 APK/bridge 摘要均单独记录，不沿用早期构建结论。该 APK 在代码提交前，以 `ceecdf1588c9ee30b8bbe9b32883342a8e04bd72` 加本轮全部源码改动构建；这些改动随后原样提交为 `2cd8d0f55046479206ec3adddb39426223d3bc0c`，提交后未另行重建，因此 APK 文件名仍携带基线 SHA。

## 显式绑定与动作语义

例如，方案可在 `editor/bindings` 声明：

```yaml
editor:
  bindings:
    Control+Up: manual_up
    Control+Down: manual_down
    Shift+Delete: manual_delete
    Control+Delete: auto_delete
```

| 方案动作 | 菜单文字 | 实际语义 |
| --- | --- | --- |
| `manual_up` | 前移一位 | 引擎移动目标候选的手动位置 |
| `manual_down` | 后移一位 | 引擎移动目标候选的手动位置 |
| `manual_delete` | 重置手动排序 | 清除目标词条的手动位置，保留自动学习数据 |
| `auto_delete` | 重置自动调频 | 重置目标词条的自动学习，保留手动位置；并非清空整份词库 |

只声明 `Shift+Delete: manual_delete` 也可显示“重置手动排序”，不要求同时声明前移和后移。EVO 不补默认键，也不增加置顶循环。`express_editor`／`fluid_editor` 及其带命名空间的处理器实例仍按 EVO 实现读取 `editor/bindings`。

有效 `auto_delete` 使用方案声明的按键，以“重置自动调频”替换等义的旧“忘记此词”入口；没有有效声明时保留旧入口。候选可能仍由系统词典或保留的手动位置提供，后续也可以重新学习；不能把“重置自动调频”解释为永久屏蔽或删除系统词典。

## 身份、生命周期与目标保护

EVO 家族标记来自载荷摘要校验后的解析结果：导入项核对 ELF SONAME，provider 项核对包名、provider ID、SONAME 与已授权记录。只有外部引擎槽配置成功后才写入 owner；不使用显示名称、版本子串或尚未生效的选择偏好推断当前引擎。该标记不是新的 C API 能力探测。

维护流程停止并重新启动同一 native 槽时保留该标记；配置入口、内置槽、启动或切换失败以及最终关闭时清除。读取动作还要求 owner 已启动、非维护状态、当前 generation 的 ready 快照。原四槽顺序保持不变，第五槽只用于 EVO 的 `auto_delete`。

动作先在同一候选快照中高亮目标，再核对实际页号与页内位置，确认后才投递按键；缺少可选配置或高亮 API 时拒绝相应动作。全局下标不能只用取模后的页内位置判定成功。过期 generation／revision 和负下标继续拒绝。

EVO 对超出总数的全局高亮会夹到最后一个候选。测试中 8 个候选、每页 3 个，请求下标 10 时实际落在第 2 页第 1 槽，而请求属于第 3 页第 1 槽；本轮拒绝投递按键，完整顺序、原始输入和提交均不变。此情形不承诺撤回引擎自身已经发生的翻页。

## 提交前复核

提交前修正了三项兼容或投递问题：EVO 引擎运行旧万象 Lua 路径时继续使用原四槽和“重置排序”标签；不再通过通用宽松解析器处理 EVO 声明，避免 `Lock+Control+Delete` 被误投递为 `Control+Delete`；完整转运所有 editor 绑定后再检查等价事件，避免 `Control+Shift+Delete` 与 `Shift+Control+Delete` 分属不同动作时误派发。

原生 EVO 发现结果采用 `evo-editor-bindings-v1` 加原始按键／action 对，包含 `noop`、其他动作及不可读动作的空占位；前端按 `(keysym, mask)` 对全部可识别事件检查重复，歧义组全部拒绝，再选择各动作的首条独立有效绑定。这里的五槽是前端解析后的动作槽；旧 Lua 四槽协议继续保持。执行菜单动作前会重新读取并检查完整绑定。

严格 EVO 编码只接受已核对的常用 X11 键名和 `Control`、`Shift`、`Alt` 修饰键，不丢弃未知前缀或修饰键；白名单以外的 keypad、国际键和多媒体键保守拒绝。`ISO_Left_Tab` 保留原 keysym，不经前端通用按键序列化归一成 `Tab`。

## 验证结果与边界

| 门禁 | 结果 |
| --- | --- |
| 主机 native 契约 | `engine-contract`、`input-statistics-contract`，2/2 通过 |
| 定向 JVM | 候选动作、引擎操作、交互契约和引擎仓库四类，78/78 通过，失败／错误／跳过均为 0；覆盖严格按键解析、全 action 冲突与旧 Lua 四槽兼容 |
| 构建 | x86_64 Debug 与 AndroidTest APK 成功 |
| APK 白名单与链接审计 | 最终 APK 两项通过；仅打包既有的三类 native 库，16 KiB 对齐，无旧 Fcitx native 依赖 |
| 实际 EVO 设备 fixture | 最终 APK 独立 instrumentation 调用，`OK (1 test)`，2.169 秒；排序、两类重置、绑定冲突拒绝与引擎重启后持久化通过 |

设备 fixture 在独立临时 profile 中编译小词典，以真实 provider 的受管执行快照启动引擎，检查完整候选顺序、页号、高亮、原始输入、空提交以及导出的 UserDB 字段；不修改用户方案或引擎选择设置。覆盖页内与全局目标、跨页移动、已高亮目标、陈旧目标拒绝以及引擎 stop/finalize/start 后的持久化。

实测初始顺序 `甲乙丙丁戊己庚辛`：丙前移后为 `甲丙乙丁戊己庚辛`，再次前移为 `丙甲乙丁戊己庚辛`；后移和手排重置分别恢复预期顺序。自动学习后的甲，其自动字段为 `[1, 1, 1]`：手移后 `position 0→2`，`manual_delete` 后 `position 2→0` 且自动字段不变；再手移并执行 `auto_delete` 后，`commits 1→-1`，`position=2` 与 `manual_stamp` 保持，旁观词条壬及其他词条均不变，停止／再启动后仍保持。所有排序及重置动作均未产生提交。

早期 fixture 失败已保留：协程接收者导致单方法选择器校验错误、启动前读取 sessionStats、把引擎高亮夹取误写成页面不变，以及未结束学习事务就停止。修正的是夹具边界；最终通过不抹去这些失败，也没有为迁就夹具添加生产侧候选预读或翻页回滚。

本轮未覆盖完整 IME 长按手势矩阵、应用进程重启、所有用户方案／Lua 过滤器和其他 ABI。独立 fixture 的引擎重启不等同于应用进程重启。菜单文字调整不改变 EVO 的两种重置语义。

本机原始结果与事前数据备份保存在以下位置，备份不属于站点附件：

```text
D:/GitHub/fx2-rime/.verify/evo-ordering-device-backup-20261126/instrumentation-review-20261126-2110.txt
D:/GitHub/fx2-rime/.verify/evo-ordering-device-backup-20261126/native-review-20261126-2113.txt
D:/GitHub/fx2-rime/.verify/evo-ordering-device-backup-20261126/evo-ordering-logcat-20261126-1930.txt
D:/GitHub/fx2-rime/.verify/evo-ordering-device-backup-20261126/app-private.tar
D:/GitHub/fx2-rime/.verify/evo-ordering-device-backup-20261126/external-files.tar
```

## 维护入口

现行使用说明见[候选窗口](../../features/candidate-window#evo-手动排序)与[用户手册](../../manual/DIRECT_RIME_USER_GUIDE_zh-CN)；引擎接入与独立设备回归见[引擎更新](../engine-update)。本轮相关源码已提交于代码 SHA `2cd8d0f55046479206ec3adddb39426223d3bc0c`；本地提交未推送：

```text
app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeCandidateSequence.kt
app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt
app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeEngineOperations.kt
app/src/main/java/org/fcitx/fcitx5/android/core/engine/RimeEngineModels.kt
app/src/main/java/org/fcitx/fcitx5/android/core/engine/RimeEngineStore.kt
direct-rime/src/main/cpp/engine.cpp
app/src/androidTest/java/org/fcitx/fcitx5/android/core/engine/RimeEvoOrderingDeviceTest.kt
```
