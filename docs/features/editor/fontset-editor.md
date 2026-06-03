# 字体集编辑器

::: warning 待补充
本页内容正在撰写修订中。
:::

fx 引入 **字体集 (Fontset)** 系统，将"哪些位置用哪个字体"配置化，配合 `config/fontset.json`。

## 可配置位置

| Key | 用途 |
|-----|------|
| `font` | 默认 AutoScaleTextView 通用字体 |
| `cand_font` | 候选词字体 |
| `key_main_font` | 按键主标签字体 |
| `key_alt_font` | 按键副标签字体 |
| `preedit_font` | 预编辑文本字体 |
| `popup_key_font` | Popup 弹窗按键字体 |

## 字体文件存放

- 应用私有目录的 `fonts/` 子目录
- 支持 `.ttf` 与 `.otf`
- 启动时缓存所有已声明字体，避免运行时重复加载

## 字体回退

- AutoScaleTextView 在主字体不含某字符时自动回退到下一字体
- 字号亦可单独配置（见 [键盘布局编辑器](/features/editor/layout-editor) 中的 fontSize 设置）

## 入口

进入 **配置 → 键盘 → 字体集** 或通过 MacroKey 的 fontset action 切换激活字体集。

## 相关页面

- [键盘布局编辑器](/features/editor/layout-editor)
- [MacroKey 编辑器](/features/editor/macrokey-editor)
