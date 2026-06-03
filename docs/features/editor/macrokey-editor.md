# MacroKey 编辑器

应用内可视化编辑 MacroKey 的工具，避免手写 step 数组。

::: tip 数据模型在哪
本页只描述编辑器的使用入口；MacroKey 的全部字段、step 类型与 JSON 结构请见 [MacroKey 细化](/features/keys/macro-key)。
:::

## 入口

在 [键盘布局编辑器](/features/editor/layout-editor) 中选择一个按键，将类型切换为 `MacroKey` 即可进入 MacroKey 编辑器。

## 编辑器可配置项

- 主标签 / 副标签 / 长按标签
- 三组宏序列：`tap` / `swipe` / `longPress`
- 每个 step 在面板中按顺序添加、拖拽重排、删除
- step 类型可在下拉中选择（按键 / 文本 / 编辑命令 / 快捷键 / app action / layer 等）
- 每个 step 类型显示对应的字段输入（如 KeyRef 选择器、文本框）
- KeyRef 选择器仅暴露 **Fcitx 键名** 列表（如 `Return` / `Shift_L` / 字母数字等）
- swipe label 可在键面上预览

::: warning 编辑器不支持 Android 键码
KeyRef 的另一种形式 `{"android": <code>}` 只在手改 JSON 时可用，编辑器界面没有对应输入项。如果按键里已经存在 Android 键码引用，**不要再用编辑器二次保存** —— 编辑器可能不识别这种条目导致信息丢失。详见 [MacroKey 细化 → KeyRef](/features/keys/macro-key#keyref-引用按键的两种方式)。
:::

## 相关页面

- [MacroKey 细化（数据模型 / step 完整列表）](/features/keys/macro-key)
- [键盘布局编辑器](/features/editor/layout-editor)
- [按键类型总览](/features/keys/overview)
- [滑动操作](/features/keyboard/swipe-actions)
