# 图标主题

入口：**设置首页 → 外观 → 图标主题**。图标主题管理按键、工具栏与系统按钮的图案；键盘主题管理背景、按键颜色和形状，两者分别选择。

## 选择、编辑与分享

在图标主题列表选择一套主题，点编辑按钮修改对应槽位。可以新建或复制主题，也可导入、导出和通过二维码分享。带外部图片资源的主题使用页面提供的 ZIP 分享方式，便于一起传递资源。

内置“谷歌”主题补充了主题切换、图标主题、设置、随机主题等操作图标；本轮新增、替换的 Material Symbols 使用细线圆角轮廓风格。随机主题使用骰子图案。

升级时，文件仍符合上一版内置内容的尺寸才会自动替换，尺寸已改变的用户版本继续保留。删除的内置图标主题文件会在下次启动时按原名重新补装。若曾升级但谷歌图标一直没有变化，本轮已修正旧版本识别与重试条件。

<span id="符号面板也使用图标主题"></span>

## 常用图标槽位

符号面板、工具栏与状态区读取以下槽位：

| 槽位 | 使用位置 |
| --- | --- |
| `toolbar.virtual_keyboard` | 返回文字键盘的按钮 |
| `keys.backspace` | 退格按钮 |
| `toolbar.random_theme` | 工具栏或状态区的随机主题动作 |
| `toolbar.ai_assistant` | 工具栏或状态区的 AI 助手动作，默认星形图标 |
| `toolbar.js_tools` | 工具栏或状态区的 JS App 直达动作；内置「有道翻译」默认有道官方字标，内置「哔哩哔哩翻译」默认哔哩哔哩标志，其他工具回退代码图标 |
| `toolbar.language_switch` | 工具栏语言切换按钮的**中文态**图标，默认「中/En」 |
| `toolbar.language_switch_ascii` | 工具栏语言切换按钮的**ASCII 态**图标，默认「En/中」 |
| `keys.language` | 键盘语言键的**中文态**图标，默认「中/En」 |
| `keys.language_ascii` | 键盘语言键的**ASCII 态**图标，默认「En/中」 |

缺少槽位或值为空时回退内置图标。符号面板监听图标主题切换并刷新功能键，分类与滚动位置继续保留。

语言相关的四个槽位按中英状态分别取用：中文态读 `toolbar.language_switch` / `keys.language`，ASCII 态读对应的 `*_ascii` 槽位。回退规则按界面不同——**键盘语言键**的 ASCII 槽位缺失时直接用内置「En/中」；**工具栏按钮**的 ASCII 槽位缺失时先沿用中文态槽位（未适配的主题两个状态显示同一图标），两者都没配才用内置「En/中」。因此只改中文态槽位不会连带改掉 ASCII 态的内置图案。

主题页随机主题卡片自身的重新随机按钮使用应用资源中的圆角轮廓骰子，与当前图标主题的工具栏槽位分别管理；右上角仍是编辑按钮。

## 字母键图标

`keys.letter.a` … `keys.letter.z` 是 26 个字母键槽位，按小写字母命名；一枚图标同时覆盖大小写两种键面（内置 qwerty 的键面字符是大写，自定义布局可能写小写）。配置后：

- 图标画在键面**底层**，字母本身隐藏；
- 字母键上的标点副标签（上滑提示）照旧叠在图标**上层**，位置仍由「标点位置」与「副标签显示在侧边」决定；
- 没配该槽位时键面与以前完全一致，回退到字母文字。

单色 SVG 跟随按键文字色，多色 SVG 保留自带配色。26 个槽位在图标主题编辑器里单独成组「字母按键」，槽位名显示为 `A`–`Z`。按键副标签本身可由键盘样式页的「显示按键副标签」开关整体隐藏，见[主题编辑器](theme-editor.md)。

## 数字键盘与扩展窗口按钮

数字键盘层（`?123` 切到的那一层）与扩展窗口的标题栏也读取图标主题：

| 槽位 | 使用位置 |
| --- | --- |
| `keys.numpad` | 键盘上的 `?123` 布局切换键 |
| `keys.numpad.0` … `keys.numpad.9` | 数字键盘的 0–9 |
| `keys.numpad.add` / `subtract` / `multiply` / `divide` / `equal` / `decimal` / `at` / `percent` | 数字键盘的运算符与符号键 |
| `keys.numpad.space` | 数字键盘的空格键 |
| `keys.numpad.layout` | 数字键盘里切回字母层的 `ABC` 键 |
| `text_editor.layout` / `select_all` / `cut` / `copy` / `paste` / `backspace` | 文本编辑栏的六个按钮 |
| `system.back` | 状态区、剪贴板、文本编辑页共用的返回键 |
| `system.editor_info` | 状态区的输入框信息按钮 |
| `system.settings` | 状态区的输入法设置按钮 |
| `system.clipboard_delete` | 剪贴板标题栏的清空按钮 |
| `system.clipboard_search` | 剪贴板标题栏的搜索按钮 |

`system.*` 槽位与工具栏一样可写 SVG，也可写文字或表情；未配置时回退内置图标。返回键在三个窗口里是同一个按钮，配一次即三处同时生效。

## 在布局里指定槽位

布局 JSON 的按键可以写 `iconSlot` 指向当前图标主题的任意槽位，例如：

```json
{ "type": "MacroKey", "iconSlot": "keys.numpad", "tap": { "macro": [ { "type": "layer", "mode": "to", "target": "数字" } ] } }
```

- 槽位对每类按键都会解析，但图标只画在带副标签或图片的键视图上：字母键、`MacroKey`、`LayoutSwitchKey`、退格、回车、语言键、占位键等；`SymbolKey`、`NumPadKey` 这类纯文字键不绘制，`SpaceKey` 只在设置 `swipeLabel` 转为副标签视图后才会绘制。
- 配置后图标画在键面底层、主标签隐藏，副标签仍叠在上层。
- `LayoutSwitchKey` 的 JSON `subLabel` 是切换目标，不显示为副标签；键面文字只有 `label`。数字层的 `ABC` 就可用 `iconSlot: keys.numpad.layout` 显示图标。

## 图标尺寸与深色适配

按键图标统一画在**固定 24 dp 的方框**里，按键过矮时按短边的 68% 收缩。图标框不随键宽变化——同一排的窄字母键与宽功能键（回车、空格、`?123`）用同一个方框，同一枚图案不会在宽键上被放大一圈。图标按 SVG 自身的 `viewBox` 等比铺满方框，所以看起来的大小差来自画稿本身（例如字母的墨迹只占 `viewBox` 的一小部分），而不是渲染尺寸不同。

深色主题下，**多色**图标里的近黑笔画（三个颜色通道都不超过 `0x30`）会换成按键文字色，色点等其它颜色保持不变；**单色**图标整体按按键文字色染色。这样同一枚图标在浅色和深色主题下都清晰，不必为深色另做一套。

## JSON 与图标值

主题包含 `name`、可选 `author`、整数 `version`、可选 `thumbnail_svg` 和 `icons`。`icons` 将槽位名映射到图标字符串。

- `keys.*` 槽位在编辑器中使用 SVG 图标。
- `toolbar.*` 和 `system.*` 可使用 SVG，也可使用文字或表情。
- 槽位值也可以是 `file:` 开头的图片引用（如 `file:button_icons/<主题名>/a.png`，相对应用外部文件目录）；导出 ZIP 时图片随包保存。
- 缩略图用于列表展示，不代替槽位内容。

例如，下面只替换退格键图标：

```json
{
  "name": "轮廓示例",
  "version": 1,
  "icons": {
    "keys.backspace": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='M9 5H21V19H9L3 12Z M12 9L18 15 M18 9L12 15' fill='none' stroke='currentColor' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/></svg>"
  }
}
```

在线编辑器也可编辑图标主题。文件、二维码和主题包的互导见[分享与导入](/features/qr-share-import)，面板行为见[符号面板](../symbol-panel.md)。
