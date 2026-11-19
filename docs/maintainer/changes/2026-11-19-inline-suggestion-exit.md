# 工具栏内嵌建议退出 · 2026-11-19

本记录对应代码默认分支 `fx-rime-only` 的提交 `dfc400711`（提交标题「修复(工具栏): 内嵌建议态显示返回箭头并可退出」）。同一轮推送还包含 `34828a3db`（issue 模板入口）与 `23b8e70be`（README 重写），后两项属于仓库入口维护，不改变文档站的现行功能说明。

## 现象与处理

| 现象 | 处理 |
| --- | --- |
| 自动填充建议占据工具栏中间区域时，最左侧按钮仍是状态区图标，点开的是状态区域 | 建议显示期间（`IdleUi.State.InlineSuggestion`）把最左侧按钮换成返回箭头，点击行为优先于剪贴板提示和状态区，符号面板与恢复键盘模式仍在它之前处理 |
| 没有出口收起常驻建议，密码管理器的条目会一直占着工具栏 | 点击返回箭头后收起建议、复位中间区域，并在本次编辑会话内忽略后续建议；`onStartInput` 复位标记，换输入框后重新接受 |
| 屏幕阅读器读到的仍是「状态区域」 | 新增 `exit_inline_suggestions` 描述，各语言补齐；自定义按钮标签仍优先于该描述 |

## 实现边界

- 内嵌建议只在 API 30+ 存在；更早版本不进入该状态，也不走退出分支。
- 退出只收起本输入法工具栏里的建议条目，不修改系统自动填充服务、不读取或保存凭据；点击建议条目仍由系统流程处理。
- 状态机与点击顺序：`KawaiiBarComponent` 的 `dismissInlineSuggestions()` 设置会话内标记并清空 `inlineSuggestionsBar`，`handleInlineSuggestions` 在标记存在时直接消费新响应；最左侧按钮的监听在 `restoreVirtualKeyboardMode`、`PickerWindow` 之后、剪贴板与状态区之前判断内嵌建议状态。
- `IdleUi.updateMenuButtonIcon()` 与 `updateMenuButtonContentDescription()` 增加内嵌建议分支；描述在用户给按钮设置了自定义标签时保持自定义值，与剪贴板提示的既有行为一致。

## 验证

| 检查 | 结果 |
| --- | --- |
| Fx Debug JVM | 2452 项，失败 0，错误 0，跳过 30；新增 `InlineSuggestionExitButtonTest` 实际执行 1 项，覆盖工具栏、建议态与恢复三种状态下的图标、可见性和描述文本 |
| x86_64 Debug APK | `:app:assembleFxDebug` `BUILD SUCCESSFUL`；产物为 `org.fcitx.fcitx5.android.fx.rime-nightly-0.1.3-835-gd20deb92-20261117-071824-8-gabda9da86-fx-x86_64-debug.apk` |
| 设备边界 | 已安装到 AVD `rime` 并确认设置页与万象键盘正常、crash buffer 为空；本机只注册了凭据管理器的自动填充服务，没有可复现的内嵌条目，因此「建议出现 → 箭头 → 退出」只在 Robolectric 与代码复审中覆盖，未写成设备端到端验收 |

文档同步范围：`docs/features/kawaii-bar.md` 补充「内嵌自动填充建议」一节；issue 模板与 README 属于代码仓库入口，不在文档站重复维护。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
