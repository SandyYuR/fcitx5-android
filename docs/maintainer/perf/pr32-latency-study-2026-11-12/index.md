# PR #32 输入延迟研究证据（2026-11-12）

研究方法、来源、区组差和限制见[正式研究记录](../../changes/2026-11-12-pr32-latency-study.md)。实际采集 driver 为 `204acdea83ae84b311b72aff154e564739eec623`，原始产品构建 driver 为 `483c2870a6136f61cd5a13c416829fcfcca274b0`；后续工具修复不改写这两个身份。

## 原始 CI 测量

下列 ZIP 保留 GitHub artifact 的原字节，下载时已逐个核对 GitHub 提供的 SHA-256。具体 artifact ID、摘要和运行链接见[证据清单](./evidence.json)。

| 批次 | 原始附件 | 接纳范围 |
| --- | --- | --- |
| Pilot 正序 | [latency-study-pilot-forward.zip](./latency-study-pilot-forward.zip) | 3 个无 trace 新进程，18 次正确提交 |
| Pilot 反序 | [latency-study-pilot-reverse.zip](./latency-study-pilot-reverse.zip) | 3 个无 trace 新进程，18 次正确提交 |
| 独立 profile | [latency-study-profile.zip](./latency-study-profile.zip) | 3 个诊断进程，18 次正确提交；不混入无 trace 比较 |
| Formal 正序 | [latency-study-formal-forward.zip](./latency-study-formal-forward.zip) | 6 区组，18 个新进程，108 次正确提交 |
| Formal 反序 | [latency-study-formal-reverse.zip](./latency-study-formal-reverse.zip) | 6 区组，18 个新进程，108 次正确提交 |

## 构建与恢复审计

下列 JSON 来自首次构建留下的原始产物。APK 摘要与恢复后的固定产品清单逐一相符，原生审计文件本身的摘要也与清单一致；这里只保存审计，不包含 APK。

| 产品 | APK 审计 | 原生链接审计 |
| --- | --- | --- |
| baseline | [APK](./baseline-apk-audit.json) | [native](./baseline-native-link-audit.json) |
| regressed | [APK](./regressed-apk-audit.json) | [native](./regressed-native-link-audit.json) |
| current | [APK](./current-apk-audit.json) | [native](./current-native-link-audit.json) |

## 来源、派生表与验证

- [正式试验 dispatch 前的预登记](./formal-plan-before-dispatch.json)
- [固定产品、APK/SDK/probe/host 摘要和恢复来源](./products.json)
- [五组独立汇总](./summary.json)
- [正式试验区组差及原值](./formal-paired-first-raw.json)
- [正式试验全部进程、首次/warm、12 项指标](./formal-all-process-values.csv)
- [正式试验独立复算](./formal-raw-recalculation.json)
- [Pilot/profile 指标表](./pilot-summary-compact.csv)
- [Pilot/profile 独立复算](./pilot-raw-recalculation.json)
- [810 次 CI 触摸的注入线程时序检查](./injection-clock-validation.json)
- [首次元数据失败时的原始签名报告](./original-baseline-tests-signer.txt)
- [本机拒绝样本的最小事件与恢复摘要](./local-clock-rejection-minimal.json)
- [本机绘制结构诊断派生摘要](./local-draw-diagnostic-summary.json)
- [全目录摘要](./checksums.sha256.txt)

完整本机 trace、设备状态、包清单和窗口信息不在公开附件中。首次 CI 构建失败与本机两次失败均在证据清单中登记，没有用恢复或重试覆盖原失败身份。绘制结构诊断已完成本轮阶段拆分，但受探针、Debug JIT 和软件图形影响，本目录不构成首次输入提速的验收。

文本附件的摘要以 Git 中保存的 LF 字节为准。此前清单有 10 项按 Windows CRLF 计算，Git 规范化后会导致跨平台校验不符；数值与原始 ZIP 均未改变。原清单和逐文件换行核验见[补充审计](../pr32-phase-review-2026-11-12/previous-study-line-ending-audit.json)。
