# Foxy 后续修复验证数据（2026-10-18）

[主报告](../../DIRECT_RIME_FOXY_FIXES_2026-10-18.md)。
产品源码固定为 `12ab22cd82297638bee75ef30b31b3decb21decb`。

- `validation.json`：源码、APK 哈希/签名与通过的构建/回归证据。
- `packaged-profile-audit.json`：最终直通 Release 和 Foxy 原 APK 的二进制 ART profile / DEX 方法核验。
- `build-metadata.json`、`resolved-baseline-prof.txt`、`signing.txt`：最终 APK 随包元数据与规则。
- `apk-signature-verification.txt`：对最终 APK 重新进行签名验证的输出；不包含签名私钥。
- `user-log-timings.json`、`late-commit-log-timings.json`：三份真机日志的脱敏时间线；无原始输入内容。
- `early-input-incomplete.zip` 与同名 JSON：键盘可见即输入的第一轮完整原始记录；14 个轮次失败，不构成有效完整 A/B 结论。

模拟器日志包含合成语料、测试进程映射、配置及系统属性；不包含用户手机原始日志、APK、模型或反编译源码。
`preedit-observer-invalid.zip` 与同名 JSON 保留第二轮严格编码读取失败：截图已有正确中文候选，不能据此判定产品丢键。

`fresh-and-invalid-rebind-send.zip` 与同名 JSON 保存第三轮全部数据；
`fresh-records.json` / `fresh-summary.json` 对两款产品所有 fresh 阶段 192 次正确选词重新核验，
fresh 阶段没有排除轮次，返场和部分发送前提错误单独记录。
`send-precondition-audit.json` 说明为什么不能把“还在准备”时的空点选称为候选丢失。

`current-binding-validation.zip` 保存修正后的补验全量记录，运行 39163211881 成功：
16 次选词与 8 次快速发送均正确，点选前八张截图人工核验已有预期候选。
`guard-*.json` 为便于查看而展开的摘要与逐次值，不与六轮 fresh 计时混合。编辑器的 CPU onDraw 不是屏幕呈现确认。


`temporary-fixture-cleanup.json` 记录临时公开 APK 分支删除及边界；测试结束后才删除。
`checksums.sha256` 校验此目录全部归档文件（不包含该清单自身）。最终 APK 保存在 Actions
artifact，不在功能分支中；其源码、APK SHA256 与证书验证分别保存。
