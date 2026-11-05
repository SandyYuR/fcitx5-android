# 图标主题

入口：**设置首页 → 外观 → 图标主题**。图标主题管理按键、工具栏与系统按钮的图案；键盘主题管理背景、按键颜色和形状，两者分别选择。

## 选择、编辑与分享

在图标主题列表选择一套主题，点编辑按钮修改对应槽位。可以新建或复制主题，也可导入、导出和通过二维码分享。带外部图片资源的主题使用页面提供的 ZIP 分享方式，便于一起传递资源。

内置“谷歌”主题补充了主题切换、图标主题、设置、随机主题等操作图标；本轮新增、替换的 Material Symbols 使用细线圆角轮廓风格。随机主题使用骰子图案。

## 符号面板也使用图标主题

左侧分组栏和 Gboard 式底栏均复用以下槽位：

| 槽位 | 使用位置 |
| --- | --- |
| `toolbar.virtual_keyboard` | 返回文字键盘的按钮 |
| `keys.backspace` | 退格按钮 |
| `toolbar.random_theme` | 工具栏或状态区的随机主题动作 |

缺少槽位或值为空时回退内置图标。符号面板监听图标主题切换并刷新功能键，分类与滚动位置继续保留。

主题页随机主题卡片自身的重新随机按钮使用应用资源中的圆角轮廓骰子，与当前图标主题的工具栏槽位分别管理；右上角仍是编辑按钮。

## JSON 与图标值

主题包含 `name`、可选 `author`、整数 `version`、可选 `thumbnail_svg` 和 `icons`。`icons` 将槽位名映射到图标字符串。

- `keys.*` 槽位在编辑器中使用 SVG 图标。
- `toolbar.*` 和 `system.*` 可使用 SVG，也可使用文字或表情。
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

在线编辑器也可编辑图标主题。文件、二维码和主题包的互导见[分享与导入](share-import.md)，面板行为见[符号面板](../symbol-panel.md)。
