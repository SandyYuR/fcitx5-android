# 滑动操作

::: warning 待补充
本页内容正在撰写修订中。
:::

fx 将一部分按键的垂直滑动行为开放为可配置项，支持：

- **滑动触发**：支持 `swipe` 字段的按键在垂直滑动后执行对应 MacroAction
- **滑动标签**：`swipeLabel` 在键面显示提示文字
- **字母备用字符**：`AlphabetKey` 的滑动固定发送 `alt` 字符，不使用 `swipe` 宏字段
- **空格滑动**：左右滑动移动光标、上下滑动发送 Up / Down，属于 SpaceKey 内置行为
- **退格滑动**：左右滑动用于选择 / 删除范围；配置 `swipe` 时仅作为垂直滑动宏触发

## 典型配置

- 字母键滑动输入 `alt` 备用字符
- 空格上下滑动跨行移动光标
- 标点键滑动切换中英文符号
控制垂直滑动采用上滑还是下滑触发的是全局选项，不是每个键独立配置四个方向。

## 编辑入口

- **键盘布局编辑器** 中按键属性面板
- 滑动标签字体可与主标签字体独立配置（见 [字体集编辑器](/features/editor/fontset-editor)）

## 相关页面

- [键盘布局编辑器](/features/editor/layout-editor)
- [MacroKey 编辑器](/features/editor/macrokey-editor)
