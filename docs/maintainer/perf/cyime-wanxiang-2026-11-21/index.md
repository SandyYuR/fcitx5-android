# 2026-11-21 CyIME 万象长句与冷缓存调查

在 Android 35 x86_64 模拟器上给中州鹅和 CyIME 部署同一套万象 v18.1.10、语法模型、配置和初始选项。配对长句的候选刷新差距随句子变化。中州鹅的冷缓存实验观察到首键缺页等待，并验证了有限预算的后台预读可以降低这种等待。后续热态调查见[按键气泡、输入调度与长句 CPU](../input-fluency-2026-11-22/)。

## 同方案输入对照

两端使用原生 x86_64 构建，目标节拍为 120 ms/键。中州鹅源码为 `899eedbf1b7eb8475f7f2a3e162e6ec014763be5`，CyIME 源码为 `3341da598dfbf99d624139d792624093a41a9851`。对照前核对生效的 `wanxiang`、部署后的默认配置、文件摘要和种子选项。连续句与停顿句共七对，14 次均准确上屏。定时比较只纳入两端同一键都落在节拍容差内且画面能匹配到候选变化的样本。[同方案 Actions](https://github.com/SandyYuR/fcitx5-android/actions/runs/44882180485) · [产品与夹具摘要](./comparison-inputs.json) · [逐次回放](./sentence-replays.json) · [配对明细](./paired-sentences.json)

| 长句与输入方式 | 配对键数 | 中州鹅松手至候选 P90 | CyIME 松手至候选 P90 |
| --- | ---: | ---: | ---: |
| 苍茫的天涯，连续两轮 | 9 / 3 | 157.13 / 112.89 ms | 55.78 / 75.97 ms |
| 苍茫的天涯，第 29 键后加停顿 650 ms，两轮 | 7 / 10 | 82.61 / 79.71 ms | 89.12 / 88.67 ms |
| 我们今天下午，连续 | 3 | 61.66 ms | 118.62 ms |
| 我今天早上，连续 | 10 | 71.57 ms | 34.96 ms |
| 如果明天天气好，连续 | 5 | 78.00 ms | 64.12 ms |

CyIME 在连续歌词和「我今天早上」中有较低的候选刷新 P90。节拍合格、双方都能看到按下反馈的配对样本很少，模拟器录屏也会量化画面时间。相同万象配置控制了方案变量，两个 APK 仍各自使用其引擎构建、前端和部署链路。以上数字只适用于本批次的成功配对。

CyIME 的跟手调查涉及按下反馈、渲染、引擎线程和候选读取。首屏候选随输入更新，后页按展开或滚动等需求读取。中州鹅同样按需获取后页。停顿后运行的是针对尚未上屏的拼音串的补查，例如漏键或邻位输入顺序颠倒。该对照使用的万象夹具没有启用 CyIME 专有的补查索引，产品也未引入这项机制。[CyIME 源码](https://github.com/Cyletix/CyIME/tree/3341da598dfbf99d624139d792624093a41a9851) · [中州鹅候选链路](../../implementation/direct-rime-architecture.md)

## 冷缓存与预热

固定同一中州鹅 APK、万象和 120 ms 触摸回放。冷缓存时，首键 `process_key` 记录到 184.80–211.71 ms，页等待为 96.95–121.45 ms。把方案目录移到应用内部存储仍能复现明显首键页等待。同一外部目录的两轮预读对照中，未预读首键为 234.25 / 298.09 ms，预读后为 78.33 / 40.32 ms，所记录的首键页等待降至零。[冷暖对照 Actions](https://github.com/SandyYuR/fcitx5-android/actions/runs/44886210001) · [文件预读 Actions](https://github.com/SandyYuR/fcitx5-android/actions/runs/44886499186) · [冷暖逐次数据](./cold-warm-keys.json)

单独的资源实验预读运行目录中的 `wanxiang-lts-zh-hans.gram` 和 `build/wanxiang.table.bin`，大小约 399 MiB 和 54 MiB。启动键盘前，两者合计驻留为 0 MiB（未预读）或 453 MiB（预读），输入完一句后为约 191 MiB 或 453 MiB。读入的是可回收的内核文件页缓存，不会写出另一份缓存文件。应用退出后页仍可保留，内存有压力时可被系统回收。辅助实验读约 453 MiB，工作进程峰值 RSS 约 6 MiB，读取过程消耗约 176–185 ms CPU。RSS 是该辅助进程的峰值，页缓存则是系统内存和存储带宽的成本。[资源实验 Actions](https://github.com/SandyYuR/fcitx5-android/actions/runs/44891924237) · [资源计数](./prewarm-resources.json)

同版 APK 的开关对照共四对，按就绪后立即输入和空闲后输入分别测量。启用预热后，四对的首键 `process_key` 都缩短，幅度为 2.23–54.03 ms。四对逐轮 `process_key` P95 都降低，幅度为 36.96–195.62 ms。可配对的三组首键候选到达时间中，两组提前 5.88 / 13.68 ms，一组晚 8.03 ms。快速输入时预热任务取消，空闲后可完成。最新正式实现延后发起，按可用内存控制预算，在实际触摸时取消，避免预读与输入长时间争抢 I/O。[应用开关对照 Actions](https://github.com/SandyYuR/fcitx5-android/actions/runs/44919923865) · [开关对照数据](./app-prewarm-ab.json) · [快速输入验证](./app-prewarm-verification.json) · [正式实现](https://github.com/SandyYuR/fcitx5-android/commit/1a03df8d17328fae5a22226270f0cf1fb068997a)
