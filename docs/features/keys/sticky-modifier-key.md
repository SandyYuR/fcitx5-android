# StickyModifierKey 粘滞修饰键

`StickyModifierKey` 可将 Ctrl、Shift 或 Alt 暂时点亮，再与下一次符合条件的单键组合。多个修饰键可以一起点亮，例如先点 Ctrl、再点 Shift，随后输入 X，发送 Ctrl+Shift+X。

## 入口与设置

在[键盘布局编辑器](/features/editor/layout-editor)中添加或编辑按键，将类型设为 `StickyModifierKey`，再选择 Ctrl、Shift 或 Alt。默认选择 Ctrl；切换修饰键时，空标签或默认的 Ctrl / Shift / Alt 标签会同步更新，已填写的自定义标签会保留。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | :---: | --- |
| `type` | string | ✓ | 固定为 `"StickyModifierKey"` |
| `modifier` | string | ✓ | 仅接受小写的 `"ctrl"`、`"shift"`、`"alt"` |
| `label` | string |  | 留空时显示对应的 Ctrl、Shift、Alt |
| `weight` | float |  | 默认 `0.1`；通用宽度规则见[按键类型总览](/features/keys/overview#宽度-weight-规则) |

以下是放入布局按键行中的三个按键对象，不是完整布局文件：

```json
[
  { "type": "StickyModifierKey", "modifier": "ctrl" },
  { "type": "StickyModifierKey", "modifier": "shift" },
  { "type": "StickyModifierKey", "modifier": "alt", "label": "Alt" }
]
```

缺少 `modifier` 或填写其他值的对象不会作为有效的粘滞修饰键加载。

`text_editor` 文本编辑底部栏不支持此类型，编辑器不会提供新增选项。旧布局或恢复草稿中的已有类型会保留，并以禁用选项提示不支持；保存该按键前，需明确改为受支持的类型。保留旧值并不表示底部栏运行时支持它，见[文本编辑底部栏](/features/text-editor#自定义底部栏按键)。

## 点亮、组合与取消

- **点击点亮**：点击一个修饰键后松手，保留点亮状态，等待下一次可消费单键。
- **再次点击取消**：再次点击同一个已点亮的修饰键，取消该键；其他修饰键保持原状态。
- **按住组合**：也可按住修饰键，再按另一个键。一次可消费单键会取用当时所有按住或已点亮的修饰键，并清除状态，松开修饰键不会重新点亮。
- **结束输入清理**：切换输入框、收起键盘或结束输入时，会清理残留的粘滞状态。

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

## Shift、符号与快捷键效果

**只有单独启用 Shift、没有同时启用 Ctrl 或 Alt，且目标是字母时，才走 Rime 的虚拟 Shift+字母组合输入。** 这时字母仍可参与方案组合并产生候选，不是直接提交一段大写文本。若 CapsKey 处于一次大写状态，这次字母输入后会复位；原本关闭的状态不会重新开启，锁定大写保持锁定。

其余可消费单键按模拟物理组合键发送，由 Rime 方案先处理，再按需要交给当前应用。Ctrl+C 等快捷键能否执行、执行什么操作，取决于方案和目标应用对组合键的支持。符号自身需要 Shift 时会保留该需求；同时点亮 Shift 不会重复叠加。

## 相关页面

- [按键类型总览](/features/keys/overview)
- [键盘布局编辑器](/features/editor/layout-editor)
- [MacroKey 宏键](/features/keys/macro-key)
- [CapsKey 大小写键](/features/keys/caps-key)
- [文本编辑底部栏](/features/text-editor)

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
