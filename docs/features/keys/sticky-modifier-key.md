# StickyModifierKey 粘滞修饰键

`StickyModifierKey` 可将 Ctrl、Shift 或 Alt 暂时点亮，再与下一次符合条件的单键组合。多个修饰键可以一起点亮，例如先点 Ctrl、再点 Shift，随后输入 X，发送 Ctrl+Shift+X。点亮在**抬手确认**时生效：按下只显示普通按压反馈，松开后才切换点亮状态，长按、滑动或滑出键外都不改变已有状态。

## 入口与设置

在[键盘布局编辑器](/features/editor/layout-editor)中添加或编辑按键，将类型设为 `StickyModifierKey`，再选择 Ctrl、Shift 或 Alt。默认选择 Ctrl；切换修饰键时，空标签或默认的 Ctrl / Shift / Alt 标签会同步更新，已填写的自定义标签会保留。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | :---: | --- |
| `type` | string | ✓ | 固定为 `"StickyModifierKey"` |
| `modifier` | string | ✓ | 仅接受小写的 `"ctrl"`、`"shift"`、`"alt"` |
| `label` | string |  | 留空时显示对应的 Ctrl、Shift、Alt |
| `weight` | float |  | 默认 `0.1`；通用宽度规则见[按键类型总览](/features/keys/overview#宽度-weight-规则) |
| `swipeUp` / `swipeDown` | object |  | 上滑 / 下滑执行的追加动作（MacroAction），见[方向滑动与长按动作](#方向滑动与长按动作) |
| `swipeUpLabel` / `swipeDownLabel` | string |  | 上滑 / 下滑标签；填写后键面才换成带副文字的外观，不受主题「标点位置」影响 |
| `longPress` | object |  | 长按执行的追加动作（`macro` 形式） |
| `altTextColor` / `altTextColorMonet` | int / hex 或 token |  | 键面副文字（方向标签）颜色；规则同[按键类型总览](/features/keys/overview#颜色字段约定) |
| `activeBackgroundColor` / `activeBackgroundColorMonet` | int / hex 或 token |  | 点亮时的键面底色；未设置时沿用主题的选中项背景色（`genericActiveBackgroundColor`） |
| `activeTextColor` / `activeTextColorMonet` | int / hex 或 token |  | 点亮时的文字颜色；未设置时沿用主题的选中项文字色（`genericActiveForegroundColor`） |

其余通用字段（`textColor`、`backgroundColor`、`shadowColor`、`rowHeightPercent`、`splitAfter` 等）与[按键类型总览](/features/keys/overview#几乎所有按键都通用的字段)一致。数值颜色按`0xAARRGGBB` 换算成十进制写入；Monet 字段也可写 `theme:` 前缀的主题 token（如 `"theme:accentKeyBackgroundColor"`）。`StickyModifierKey` 不提供「合成态按键」覆盖：点亮状态与整键替换的合成态互斥。

以下是放入布局按键行中的三个按键对象，不是完整布局文件：

```json
[
  { "type": "StickyModifierKey", "modifier": "ctrl" },
  { "type": "StickyModifierKey", "modifier": "shift" },
  { "type": "StickyModifierKey", "modifier": "alt", "label": "Alt" }
]
```

带点亮配色与追加动作的写法：

```json
{
  "type": "StickyModifierKey",
  "modifier": "ctrl",
  "activeBackgroundColor": 4282315189,
  "activeTextColorMonet": "onPrimary",
  "swipeUpLabel": "Esc",
  "swipeUp": { "macro": [{ "type": "tap", "keys": [{ "fcitx": "Escape" }] }] },
  "longPress": { "macro": [{ "type": "tap", "keys": [{ "fcitx": "Caps_Lock" }] }] }
}
```

缺少 `modifier` 或填写其他值的对象不会作为有效的粘滞修饰键加载。

`text_editor` 文本编辑底部栏不支持此类型，编辑器不会提供新增选项。旧布局或恢复草稿中的已有类型会保留，并以禁用选项提示不支持；保存该按键前，需明确改为受支持的类型。保留旧值并不表示底部栏运行时支持它，见[文本编辑底部栏](/features/text-editor#自定义底部栏按键)。

## 点亮、组合与取消

- **按下只回显，抬手才点亮**：按住修饰键时只出现普通按压高亮，键盘状态不变；松开（一次有效单击）后才点亮，等待下一次可消费单键。
- **再次单击取消**：再次单击同一个已点亮的修饰键，取消该键；其他修饰键保持原状态。
- **按住不放不切换**：长按、上滑 / 下滑、滑出键外或触摸被系统取消时，都不改变点亮状态，`longPress` / `swipeUp` / `swipeDown` 配置的动作照常执行，并按「按下前」的状态决定是否带修饰。
- **多指组合**：也可以一只手按住修饰键、另一只手同时按下目标键。一次可消费单键会取用当时所有已点亮的修饰键，并清除点亮状态。
- **点亮的修饰键被消费后不重新点亮**：修饰键仍被按住、同时被另一个手指导入的按键消费时，松手只结束这一次按压，不会把刚被消费的修饰键重新点亮；被消费的按压未松开前，同一修饰键的再次按下也按重复按压处理。
- **结束输入清理**：切换输入框、收起键盘、结束输入或按键视图离开窗口时，会清理残留的粘滞状态与尚未抬手的按压。

## 哪些动作会消费状态

“消费”指让当前修饰键参与这一次按键，随后清除其点亮状态。是否消费同时取决于触发方式和实际动作：

| 触发与动作 | 是否消费 |
| --- | --- |
| 点击、直接长按、上滑或下滑，实际执行一个普通按键 | 是 |
| 上述触发方式执行的宏，且宏中只有一个 `tap` 步骤、`keys` 中只有一个 `fcitx` 键 | 是，按单键处理 |
| 重复触发、双击、左右滑动 | 否 |
| 打开弹出菜单、选择弹出菜单项 | 否 |
| 多步宏、包含多个键的宏、输入文本或其他动作 | 否 |

例如，长按若直接发送一个单键，可以消费；长按若打开弹出菜单，则不会消费。未消费的动作照常执行，点亮状态留给之后符合条件的单键。宏的字段格式见[MacroKey](/features/keys/macro-key#macroaction-结构)。

## 点亮外观

点亮状态只改变键面颜色，不额外叠加常亮的前景填充：有边框主题下键底换成激活背景色，无边框主题下点亮时才补一层激活底色，文字颜色同步切换；未配置激活配色时沿用主题的选中项背景与文字色。按下修饰键本身仍显示通用按压高亮，与普通按键一致。水波纹的遮挡判定按当前实际底色（普通态或点亮态）计算，主题切换、键底重新绑定和视图重新挂载后都会刷新。

## 方向滑动与长按动作

`StickyModifierKey` 的主行为永远是单击点亮 / 取消。上滑、下滑和长按可以各自附加一个宏动作：

- 填写方向标签后，键面才从普通文字换成带副文字的外观（上滑标签画在键上方、下滑标签画在键下方）；不填标签时保持普通文字键面，方向动作照常执行；
- 这些动作不切换、不消费粘滞状态，按「按下前」的点亮情况决定是否带修饰；
- 用编辑器录制方向 / 长按动作后，宏编辑器可能自动填入与动作对应的方向标签，保存时随布局写入；清空方向动作会清掉对应字段，长按清空则移除 `longPress`；
- 方向动作、方向标签与长按动作都能在键编辑器里配置，与 MacroKey 使用同一套宏录制入口，字段格式见 [MacroAction](/features/keys/macro-key#macroaction-结构)。

## Shift、符号与快捷键效果

**只有单独启用 Shift、没有同时启用 Ctrl 或 Alt，且目标是字母时，才走 Rime 的虚拟 Shift+字母组合输入。** 这时字母仍可参与方案组合并产生候选，不是直接提交一段大写文本。若 CapsKey 处于一次大写状态，这次字母输入后会复位；原本关闭的状态不会重新开启，锁定大写保持锁定。

其余可消费单键按模拟物理组合键发送，由 Rime 方案先处理，再按需要交给当前应用。Ctrl+C 等快捷键能否执行、执行什么操作，取决于方案和目标应用对组合键的支持。符号自身需要 Shift 时会保留该需求；同时点亮 Shift 不会重复叠加。

## 相关页面

- [按键类型总览](/features/keys/overview)
- [键盘布局编辑器](/features/editor/layout-editor)
- [MacroKey 宏键](/features/keys/macro-key)
- [CapsKey 大小写键](/features/keys/caps-key)
- [文本编辑底部栏](/features/text-editor)
- [主题编辑器](/features/theme/theme-editor)（选中项背景 / 文字色）

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)