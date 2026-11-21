# MacroKey 编辑器

应用内可视化编辑 MacroKey 的工具，避免手写 step 数组。

::: tip 数据模型在哪
本页只描述编辑器的使用入口；MacroKey 的全部字段、step 类型与 JSON 结构请见 [MacroKey 细化](/features/keys/macro-key)。
:::

## 入口

在 [键盘布局编辑器](/features/editor/layout-editor) 中选择或设置 `MacroKey`，在按键表单中配置标签、显示文字，再从点击、长按或划动的动作项打开宏动作编辑器。

宏动作编辑器以对话框叠加在当前页面上。确认后把动作带回原表单，再按所在编辑页的保存方式写入配置。返回或取消时按未保存修改提示处理。

## 编辑器可配置项

- 主标签 / 副标签 / 长按标签
- **显示文本**：可按方案分别设置；显式填写的值**按原样显示**（优先于标签，不再被 Shift 改写成小写），留空回落到标签——预览与保存遵循同一优先级
- 三组宏序列：`tap` / `swipe` / `longPress`（`swipe` 在支持方向标签的键型上分为**上滑 / 下滑**两栏，可分别配置动作与标签；填了标签时标签按方向画在键的上方 / 下方，方向只由字段决定，不随主题「标点位置」变化）
- 每个 step 在面板中按顺序添加、拖拽重排、删除
- step 类型可在下拉中选择（按键 / 文本 / 编辑命令 / 快捷键 / app action / layer 等）
- **app action** 列表包含本版新增的「切换系统输入法」与「弹出 Rime 方案选单」
- 每个 step 类型显示对应的字段输入（如 KeyRef 选择器、文本框）
- KeyRef 选择器仅暴露 **Fcitx 键名** 列表（如 `Return` / `Shift_L` / 字母数字等）
- 划动标签可在键面上预览

## 应用动作快捷入口

包含这批入口的构建支持以下动作，可放到点击、划动或长按宏中：

| 动作 | ID | 用途 |
| --- | --- | --- |
| 输入统计与字词云 | `settings_input_stats` | 打开[统计页](/features/input-statistics)，不自动开启记录 |
| 语音输入 | `voice_input` | 使用当前语音输入入口，沿用录音权限流程 |
| AI 助手 | `ai_assistant` | 打开或关闭当前助手，沿用插件授权和预览确认上屏 |
| 分体键盘开关 | `split_keyboard` | 切换分体设置；实际显示仍受屏幕宽度阈值影响 |
| 日夜主题切换 | `theme_toggle` | 切换已配置的日间、夜间主题 |
| 随机主题 | `random_theme` | 随机选择主题 |
| 键盘布局管理 | `text_keyboard_layout_profiles` | 打开完整布局管理页，与编辑当前布局、快速切换布局分别提供 |
| JS 插件设置 | `settings_js_plugins` | 打开语音与工具插件管理页，进入页面不代表授予权限 |
| 自定义更新 | `settings_scheme_updates` | 打开方案更新任务页，不自动启动更新或部署 |
| 数据与备份 | `settings_data_backup` | 打开数据管理页，不自动备份或恢复 |

::: warning 编辑器不支持 Android 键码
KeyRef 的另一种形式 `{"android": <code>}` 只在手改 JSON 时可用，编辑器界面没有对应输入项。如果按键里已经存在 Android 键码引用，**不要再用编辑器二次保存** —— 编辑器可能不识别这种条目导致信息丢失。详见 [MacroKey 细化 → KeyRef](/features/keys/macro-key#keyref-引用按键的两种方式)。
:::

## 相关页面

- [MacroKey 细化（数据模型 / step 完整列表）](/features/keys/macro-key)
- [键盘布局编辑器](/features/editor/layout-editor)
- [按键类型总览](/features/keys/overview)
- [滑动操作](/features/keyboard/swipe-actions)
