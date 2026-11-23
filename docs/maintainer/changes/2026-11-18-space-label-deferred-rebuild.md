# 空格标签与键盘状态修复 · 2026-11-18

本记录对应代码默认分支 `fx-rime-only` 的提交 `8127188008f47e385aa3a660e4e796a31f7d9994`。本次从 review 分支中只挑选五项修复直接应用到默认分支，未带入按键 SVG 主题、水彩主题及其专属断言等其它提交。

> **后续复核：** 同批进入默认分支的五个提交依次为 `b856c244`（空格键自定义标签在合成态与重建后丢失）、`c74347d3`（旧图标主题在 ASCII 态掉回内置语言图标）、`7155f7e3`（副标签侧边位置在 RTL 下左右翻面）、`d73753f1`（单字母空格键标签被 Caps 改写成大写）与 `81271880`（延迟重建后刷新空格键标签）；下表第一行对应首尾两个提交。对照登记见[2026-11-22 提交对照](./2026-11-22-code-doc-gap-review)。

## 覆盖范围

| 现象 | 处理 |
| --- | --- |
| 空格键自定义标签在合成态或视图重建后丢失 | 延迟视图替换完成后，从当前方案状态重新刷新空格键默认标签；非空覆盖仍优先 |
| 单字母空格标签被 Caps 状态改写 | 标签显示路径保留用户输入的单字母，不把 Caps 转换应用到覆盖标签 |
| 旧图标主题在 ASCII 状态缺少配置槽位 | 配置图标不可用时回退到内置的语言切换图标，而不是留下空图标 |
| RTL 布局的副标签侧边方向相反 | 对侧边位置和布局方向统一换算，保持左侧／右侧语义一致 |

## 空格键延迟重建机制

`BaseKeyboard.flushDeferredComposeRecreations` 完成延迟重建、旧视图替换和水波纹遮挡物更新后，调用 `onDeferredComposeRecreationsFlushed`。`TextKeyboard` 覆盖该回调并执行 `refreshSpaceLabelFromState()`，避免合成回调只刷新已经被替换的旧视图。

默认标签仍由当前方案和中英状态决定；`SpaceKey.label` 去除首尾空白后有内容时作为覆盖值，覆盖值为空时继续显示方案名规则。滑动、长按和密码框显示规则没有因这次刷新改变。

## 验证

| 检查 | 结果 |
| --- | --- |
| Fx Debug JVM | 2447 项，失败 0，错误 0，跳过 30；跳过项仍按门禁规则单独计数，没有把跳过写成通过 |
| x86_64 Debug APK | `BUILD SUCCESSFUL`；APK 原生白名单和 16384 page size 门禁通过；APK SHA-256 为 `7dac75e1da667ff862f8f190867c9ab5bc5188d21340466759e333fee42c743b` |
| Direct Rime 链接审计 | 通过；librime 130、spdlog 5、marisa 9、opencc 19、yaml-cpp 24、leveldb 34、lua 32 个对象，`fcitx_cpp_symbols` 为空 |
| 设备边界 | 本次直推前未重新做真实按住空格触摸复现；Robolectric 覆盖合成态、重建后标签和覆盖标签路径，设备结果不扩写为本次新的端到端验收 |

代码默认分支已由 `d20deb92e11215e3a7fe93985547eeccb4cdda3b` 直推到上述提交；文档只记录已完成的本地门禁和明确的未运行项。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)