# 2026-10-19 首次选词 I/O 与日志导出证据

结论、口径和限制见[主报告](/maintainer/history/DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19)。
产品源码为 `9adb694932df4340bc48d359af2c788ad6809f3e`；文档归档提交不改变产品代码。
原始手机日志和其中的个人输入不入库。CI 数据只来自固定测试语料。

| 文件 | 用途 |
| --- | --- |
| `validation.json` | 产品源码、最终签名包、CI 与已完成/未完成验证的索引 |
| `checksums.sha256` | 本目录证据文件的 SHA256，不包含校验表自身 |
| `sanitized-phone-timing.json` | 两份用户日志的脱敏计时，来自旧 `12ab22cd` 手机包，不是新包验收 |
| `phone-9adb-0117-sanitized.json` | 后续新 `9adb6949` Release 的同机反馈：两条首次选词及对应提交/位置回报；同一已运行进程，不是两次冷启动 |
| `cold-probe-39180494011.zip` | 最终独立万象诊断的原始 CI artifact，含测试 fixture 哈希、结果及合成输入日志 |
| `cold-probe-39180494011-result.json` | 六次重建后的后端操作完成时间及提交长度断言 |
| `cold-probe-39180494011-fixture.json` | 原方案、模型、两份用户 YAML 及临时 Lua 探针的哈希 |
| `cold-probe-39180494011-timing.json` | 从该运行日志提取的原生/Lua 分段计时与 400 条存储校验 marker |
| `frontend-9adb-results.zip` | 完整前端 Android 设备报告：15 项执行通过、1 项条件跳过 |
| `unit-tests-9adb.zip` | 从最终 ARM64 Debug artifact 单独保留的 JVM HTML 报告：491 项通过 |
| `leveldb-reuse-host-ci.json` | CI 实际执行的公共 API 恢复、WAL 轮转、显式 sync 与产品桥接契约结果 |
| `leveldb-reuse-host-contract.json` | 同一机制的本地主机预验证记录；不替代上面的最终 CI 记录 |
| `release-*` | 最终签名 Release 的元数据、哈希、签名与 ART profile 审计，不存 APK 二进制 |
| `cold-probe-39178602959*` | 定位过程首轮；WARNING marker 被过滤、卸载后 JSON 丢失，仅采用有效原生记录 |
| `cold-probe-39179387776*` | 定位过程第二轮，补齐库级 marker；首次因 GitHub 403 失败，重试该作业通过 |

这不是产品前后对照：三轮探针的源码、fixture、运行负载不同，不能将数字相减。
独立万象项在最终常规前端套件中按条件跳过，在工作流 `39180494011` 中单独实际执行。
400 条记录每条 16KiB 的 Android 存储回归运行于临时探针专用数据库，不读取用户词库。
所有数据均不足以证明用户 Android 16 真机的 145ms 尖峰已消失或完成断电耐久性验证。
