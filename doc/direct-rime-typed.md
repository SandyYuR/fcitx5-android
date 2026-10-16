# 真机验证记录（2026-10-16，第三包，s-typed）

## logcat 摘录（冷启动，pid 28741，14:30:55）

```
service.onCreate begin
DirectRimeStart: enter state=STOPPED
DirectRimeStart: sync begin
DirectRimeStart: sync end
DirectRimeStart: setup=true shared=/data/user_de/0/.../usr/share/rime-data user=/data/user_de/0/.../rime
DirectRime: setup=true ... version=1.17.0
DirectRimeStart: session=-5476376611609922984
DirectRime: session=-5476376611609922984
DirectRimeStart: ready
service.onCreate duration=84ms
```

- 无 `jclass has wrong type` Abort，无 SIGABRT，`-b crash` 缓冲为空。
- B 方案止血成立：@JvmStatic 生效（javap 已证 static），引擎启动链 sync/setup/session/ready 全通。

## 按键证据（同一次复现，14:31:02）

```
FcitxInputMethodService: sendSimulatedKeyEvent: keyCode=29 scanCode=30 action=0 unicodeChar=97
FcitxInputMethodService: forwardKeyEvent: keyCode=29 scanCode=30 action=0 unicodeChar=97 sym=0x0061
```

按键到 UI 转发层，有震动反馈，但无上屏、无候选。

## 用户确认记录（原话）

- "按完了，情况没变化啊"
- "按键有震动，用户界面都正常，但是没有上屏任何文字"
- "进不去中州韵设置：从设置页进去点的里面没东西，显示无可用配置项，该附加组件可能在fcitx启动时处于禁用状态，fcitx全局设置也没东西，一样的提示"

## 结论

- "静态补注解"假设的崩溃消失部分成立（无同点 Abort + 引擎启动）。
- 判据第三条"用户确认能打出字"未满足：有震动无上屏。
- 设置页空是预期内：DirectRimeEngine 的 getGlobalConfig 返回空 RawConfig、statusArea 返回空数组（交接已知缺口），旧 fcitx 附加组件页在 fcitx 删除后报无可用配置项属连带现象，不作直连判据。
- 下游诊断（dispatchKey / native 门控 / 事件回流）另起步骤。
