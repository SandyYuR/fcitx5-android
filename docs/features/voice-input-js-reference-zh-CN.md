---
docKind: reference
---

# Foxy JavaScript 插件接口参考（中文版）

::: info 来源与适用范围
本文对应用户提供的新版 `js-plugin-README.md`，与[英文参考](./voice-input-js-en)同步，替换旧版仅介绍 `voice.asr` 的说明。
以下正文描述 **Foxy** 的接口。中州鹅的实际支持范围见[宿主实现](/maintainer/implementation/js-voice-plugins#js-app-test-branch)。

中州鹅的 AI 助手与 JS App 功能已于 2026-11-05 合入默认分支 `fx-rime-only`；较早的 APK 需更新后使用。
已实现 App 注册、权限、结构化原生界面、编辑器、密钥别名和非流式 HTTP；App 的 SSE/WebSocket、旧式平铺布局、任意 `js.*` 布局命令、通用 App 选择器和 `foxy.websocket.authorize()` 尚未实现。语音 WebSocket 仍可使用。

Foxy 要求请求主机在静态 `hosts` 中声明。中州鹅的 **App HTTP** 还允许从用户配置的 HTTPS 地址提取主机，并在首次请求时逐域名授权；内置助手的 `hosts` 为空。这个扩展不等于给原版 Foxy 放开任意域名，语音接口仍按静态清单授权。

“确认”“发送”等标签的含义由插件的按钮事件处理决定，宿主不会自动把它们解释成 AI 请求或上屏操作。
:::

[安装与 AI 助手操作流程](./voice-input-js#ai-assistant) · <a href="/fcitx5-android/reference/js-plugin-README.md" download>下载原始英文 README</a>

Foxy 从 **设置 → JavaScript plugins** 导入一个自包含的 JavaScript 源文件。`plugins/` 里的文件是源码示例，不会随 Foxy APK 打包；把 JavaScript 文件分享到 Foxy 也能安装。声明了网络主机的插件，会在导入时请求用户批准。

运行时有两类可执行集成：

- `voice.asr`：流式或单次请求的语音识别引擎。
- `foxy.app.register(...)`：从键盘布局或工具栏启动的前台 App 能力。

其他 feature 元数据会保留，但只有宿主提供了对应消费者才会执行。只提供设置的插件也是合法的。

## 插件定义

使用 `foxy.plugin.register()` 注册一次：

```javascript
foxy.plugin.register({
  id: "example-plugin",
  name: "示例插件",
  version: "1.0.0",
  hosts: ["api.example.com"],
  features: [],
  configure(ui, locale) {
    ui.secret("apiKey", "API 密钥");
  }
});
```

`id` 须匹配 `[a-z0-9][a-z0-9._-]{0,63}`。更新时保持 ID 和字段 ID 稳定，Foxy 才能保留对应设置与密钥。`name`、`version` 是显示元数据。feature ID 使用同样的标识格式；feature type 使用小写字母、数字、点和连字符。

`hosts` 只能填写精确主机名，不能带协议、路径、端口、通配符或子域名模式。无需联网的插件使用空列表。

主机必须在 `hosts` 中声明，声明本身不等于授权。首次 HTTP 或 SSE 请求访问已声明但未批准的主机时，Foxy 会弹出确认；用户允许后保存批准记录。未声明的主机不能在运行期获得批准。

WebSocket 的 `connect` 是同步接口，首次连接未批准的主机前，应调用 `await foxy.websocket.authorize("wss://api.example.com/...")` 或 `await foxy.websocket.authorize("api.example.com")`，先完成相同的授权确认。

> 上述 WebSocket 预授权和 SSE 是 Foxy 接口；中州鹅的支持差异见页首。

## 设置

`configure(ui, locale)` 声明原生设置控件。`locale` 是当前 BCP-47 语言标签，每次打开插件设置都会重新执行 `configure`。

```javascript
configure(ui) {
  ui.text("endpoint", "端点", "https://api.example.com/v1");
  ui.secret("apiKey", "API 密钥", {
    description: "由 Foxy 保存，密钥值不会传给 JavaScript。"
  });
  ui.select("model", "模型", [
    {label: "快速", value: "fast-model"},
    {label: "准确", value: "accurate-model"}
  ], "fast-model");
  ui.switch("partials", "中间结果", true);
  ui.number("timeout", "超时", "30");
}
```

可用声明：

- `ui.text(key, label, defaultValue = "", settings = {})`
- `ui.secret(key, label, settings = {})`
- `ui.select(key, label, options, defaultValue = "", settings = {})`
- `ui.switch(key, label, defaultValue = false, settings = {})`
- `ui.number(key, label, defaultValue = "", settings = {})`

下拉选项可以是字符串，也可以是 `{label, value}` 对象。`allowCustom: true` 允许自定义值。`settings.description` 增加说明文字。`settings.visibleWhen` 支持 `{key, value}` 或 `{all: [{key, value}, ...]}`。

普通值以字符串传入，开关为 `"true"` 或 `"false"`。密钥由 Foxy 加密保存，不会出现在插件回调的 context 中。

## App 能力

一个插件可以注册多个前台 App。App ID 对应它的命令：`llm_assistant` 注册为 `js.llm_assistant`。App ID 在所有已安装插件之间是全局的，应避免重复。

```javascript
foxy.app.register({
  id: "rewrite",
  label: "改写",
  permissions: ["editor.read_selection", "editor.composition", "editor.commit"],
  async onOpen(context) {
    if (context.event === "open") {
      const selection = await foxy.editor.getSelection();
      return foxy.ui.dialog.show({
        maxWidth: 320,
        layout: {
          type: "column",
          gap: 6,
          children: [
            {type: "text", text: "改写", size: 14, bold: true},
            {type: "input", id: "input", label: "文本", multiline: true, value: selection, height: 72},
            {type: "row", align: "end", children: [
              {type: "button", id: "close", label: "关闭"},
              {type: "button", id: "send", label: "发送", style: "filled"}
            ]}
          ]
        }
      });
    }
    if (context.event === "close") return {close: true};
    return {close: true};
  }
});
```

在 Foxy 布局中这样调用：

```json
{"type":"app","command":"js.rewrite"}
```

同一个命令也可以在 Foxy 工具栏动作编辑器中选择。每个 App 收到：

- `context.event`：`"open"` 或上一个界面的按钮 ID。
- `context.values`：按钮事件发生时的输入框、下拉框值。
- `context.appId`、`context.command`。
- `context.settings`：保存的普通设置。
- 为兼容旧插件，普通设置也作为 context 顶层别名传入。
- 获得 `credentials.list` 权限后，`context.configuredSecrets` 包含已配置的密钥字段 ID；其中没有密钥值。

处理器可以返回界面描述、`{dialog: spec}` 或 `{close: true}`。按钮回调会携带新事件和值再次调用 `onOpen`。宿主关闭 App 时会取消运行时，并清除临时编辑器组合文本。

> 这个示例展示注册与布局，不包含 AI 请求。联网需要声明 `network.request` 权限，并在相应按钮事件中调用 HTTP 接口。中州鹅还会把下拉框 ID 作为事件名回传；应显式处理这些事件，不能将所有未知事件都当作关闭。

### 声明式布局节点

插件维护布局树，Foxy 根据当前键盘主题使用原生控件渲染：

| 节点 | 字段 |
| --- | --- |
| `column`、`row` | `children`、`gap`、`align`（`start`、`center`、`end`）；每个子节点可指定 `weight` |
| `text` | `text`、`size`、`muted`、`bold` |
| `input` | `id`、`label`、`labelPlacement`、`placeholder`、`multiline`、`height`、`value`、`weight` |
| `select` | `id`、`label`、`labelPlacement`、`options`、`value`、`height`、`weight` |
| `button` | `id`、`label`、`style`（`outlined`、`filled`）、`weight` |
| `spacer` | `height`、`weight` |

下拉选项可以是字符串或 `{label, value}`。标签默认在控件左侧；`labelPlacement: "top"` 将标签放在上方。没有 `weight` 时，子项不会拉伸。界面的 `maxWidth`、`maxHeight` 是以 dp 为单位的边界。Foxy 保留旧版平铺 `fields` / `buttons` 格式以便兼容。

中州鹅还支持 `input.readOnly: true`，用于允许选择和复制、但不接收键盘输入的结果框。旧式平铺格式不在当前适配范围内。

### 增量更新界面

流式或逐步结果可以直接更新控件，无需重建布局：

```javascript
await foxy.ui.dialog.setLoading(true);
await foxy.ui.dialog.setValue("result", "部分文本");
await foxy.ui.dialog.setEnabled("send", false);
await foxy.ui.dialog.setError("临时错误");
await foxy.ui.dialog.update(nextDialogSpec);
await foxy.ui.dialog.close();
```

`setValue` 接受输入框或下拉框 ID。`setError` 在当前界面中显示高度受限的错误提示。这些方法是异步宿主调用，操作顺序有要求时应使用 `await`。

## 宿主权限

首次使用 App 时，Foxy 会请求批准该 App 声明的权限；可在插件设置菜单中撤销。每个已注册 App 都隐含 `ui.dialog` 能力。

| 权限 | API |
| --- | --- |
| `editor.read_selection` | `foxy.editor.getSelection()` |
| `editor.read_text` | `foxy.editor.getText()` |
| `editor.composition` | `foxy.editor.setComposition(text)`、`foxy.editor.clearComposition()` |
| `editor.commit` | `foxy.editor.commit(text)` |
| `network.request` | HTTP、SSE、WebSocket API |
| `credentials.list` | `foxy.credentials.list()` |

编辑器 API 操作当前应用的编辑框。`getText()` 最多返回 64 KiB 文本。`setComposition()` 在宿主应用中显示临时组合文本；`commit()` 清除组合并提交指定文本；`clearComposition()` 移除临时组合。

中州鹅的助手文本框通过本地输入连接接收键盘输入；上述 `foxy.editor` API 操作打开助手之前的应用编辑框。取消生成预览时会恢复被选中的原文。

## 密钥

通过 `ui.secret` 声明密钥，网络请求的 `credential` 引用该字段 ID。Foxy 将保存的密钥注入请求，JavaScript 不会收到明文。

```javascript
const aliases = foxy.credentials.list();
// 例如：["deepseekKey", "weatherApiKey"]
```

`credentials.list()` 需要同名权限，只返回已配置的密钥 ID。未获批准时，`context.configuredSecrets` 为空。

## HTTP 请求

```javascript
const responseText = await foxy.http.request({
  url: "https://api.example.com/v1/action",
  method: "POST",
  credential: "apiKey",
  authHeader: "Authorization",
  authPrefix: "Bearer ",
  headers: {"Content-Type": "application/json"},
  body: JSON.stringify({query: "hello"})
});

const response = JSON.parse(responseText);
// {status: 200, ok: true, headers: {...}, body: "..."}
```

支持 `GET`、`POST`、`PUT`、`PATCH`、`DELETE`。`credential` 可省略；指定时必须对应已声明且已配置的密钥字段。`Content-Type` 用作请求体的媒体类型；其他由传输层控制的头和敏感头会被拒绝。非 2xx 响应会返回 `ok: false` 和响应体。禁止重定向。

旧版 `foxy.http.post(url, secretField, body, headersJson = "{}")` 继续供语音插件使用，固定发送 JSON POST、使用 Bearer 认证，非 2xx 响应会拒绝 Promise。

## 流式 HTTP / SSE

> 本节为 Foxy 参考，中州鹅的 JS App 尚未实现这些流式接口。

`foxy.http.stream(options)` 立即返回流 ID。每个 SSE 事件以 `(streamId, event, data)` 传入；非 SSE 响应逐行传入，事件名为 `"line"`。

```javascript
let streamId = null;
let output = "";

foxy.http.onChunk(async (id, event, data) => {
  if (id !== streamId) return;
  output += data;
  await foxy.ui.dialog.setValue("result", output);
});
foxy.http.onEnd(id => {
  if (id === streamId) foxy.ui.dialog.setLoading(false);
});
foxy.http.onError((id, message) => {
  if (id === streamId) foxy.ui.dialog.setError(message);
});

streamId = foxy.http.stream({
  url: "https://api.example.com/v1/stream",
  method: "POST",
  credential: "apiKey",
  headers: {"Accept": "text/event-stream", "Content-Type": "application/json"},
  body: JSON.stringify({prompt: "hello"})
});

foxy.http.cancelStream(streamId);
```

解析 SSE 的 `event:`、`data:` 字段，多行 `data:` 以换行连接。流仅使用 HTTPS，与普通 HTTP 共用主机授权及 `network.request` 权限；App 关闭时取消。分块回调按顺序等待完成，提供背压。

## WebSocket

> 中州鹅已实现语音 WebSocket；以下按事件注册的 App WebSocket 和 `authorize` 接口仍是 Foxy 参考。

普通 App 与语音插件共用 Foxy 的宿主 WebSocket 传输：

```javascript
foxy.websocket.onMessage((socketId, text) => {});
foxy.websocket.onBinary((socketId, base64) => {});
foxy.websocket.onError((socketId, message) => {});
foxy.websocket.onClose((socketId, expected) => {});

const socketId = foxy.websocket.connect(
  "wss://api.example.com/socket",
  "apiKey",
  "Authorization",
  "Bearer ",
  "{}"
);
await foxy.websocket.waitOpen(socketId);
foxy.websocket.send(socketId, JSON.stringify({subscribe: true}));
foxy.websocket.sendBinary(socketId, base64Payload);
foxy.websocket.close(socketId);
```

`connect(url, secretField, authHeader = "Authorization", authPrefix = "Bearer ", headersJson = "{}")` 返回不透明句柄。预签名 URL 可以传入空的密钥字段名。`sendGzipFrame(socketId, prefixBase64, payloadBase64)` 发送 `prefix + 4 字节大端压缩长度 + gzip(payload)` 二进制帧。帧大小有界。

连接使用 WSS、不跟随重定向，并核对插件声明和批准的主机及 `network.request` 权限。每个 JS 运行时注册一次事件处理器；插件主动关闭时，`onClose` 收到 `expected = true`。

## 编辑器 API

```javascript
const selection = await foxy.editor.getSelection();
const documentText = await foxy.editor.getText();
await foxy.editor.setComposition("临时结果");
await foxy.editor.commit("提交的结果");
await foxy.editor.clearComposition();
```

`getSelection()`、`getText()` 分别需要对应读取权限。`setComposition`、`clearComposition` 需要 `editor.composition`；`commit` 需要 `editor.commit`。宿主限制文本输入大小。

## 语音识别生命周期

ASR 需要一个 `voice.asr` feature。`sampleRate` 会被夹到 8000–48000 Hz，宿主按该采样率提供单声道、有符号 16 位小端 PCM。

文件必须自包含，不提供 imports、`require`、浏览器 API 或任意网络接口。

```javascript
foxy.plugin.register({
  id: "example-asr",
  name: "示例语音识别",
  version: "1.0.0",
  hosts: ["speech.example.com"],
  features: [{type: "voice.asr", id: "recognizer", label: "示例语音识别", sampleRate: 16000}],
  configure(ui) { ui.secret("apiKey", "API 密钥"); },
  async start(context) {},
  async audio(context) {},
  async stop(context) {},
  onMessage(event) {}
});
```

Foxy 在一个 QuickJS 会话中串行调用插件回调，方法可以是异步的：

1. `start(context)`：每次识别会话调用一次，context 含普通设置、`sampleRate`、唯一 `taskId`。返回字符串 handle，之后通过 `context.socket` 传回。非联网引擎可返回 `"single"` 等哨兵值。
2. `audio(context)`：每段 PCM 调用一次，context 含 Base64 `pcm`、`sampleRate`、`taskId`、`socket`。流式引擎转发分片；单次请求引擎缓冲时必须严格限制大小和时长。
3. `onMessage({socket, message})`：处理文本帧；二进制帧使用 `{socket, binaryBase64}`。
4. `stop(context)`：排队的音频送完后调用，在这里发送结束帧或提交缓冲音频。

回调可以返回动作：

```javascript
{ready: true}
{partial: "目前识别到的文字"}
{final: "完整短语"}
{error: "识别失败"}
{ended: true}
```

动作可以组合，例如 `{final: "你好", ended: true}`。也可调用 `foxy.voice.ready()`、`partial(text)`、`final(text)`、`error(message)`、`ended()` 发出同样的事件。返回 `{error: message}` 会中止会话；`{ended: true}` 正常结束会话。

以下只是传输骨架，需要按服务文档替换协议帧和响应解析：

```javascript
async start(context) {
  const socket = foxy.websocket.connect(context.endpoint, "apiKey");
  await foxy.websocket.waitOpen(socket);
  await foxy.websocket.send(socket, JSON.stringify({type: "start", sample_rate: context.sampleRate}));
  return socket;
},
async audio(context) {
  foxy.websocket.sendBinary(context.socket, context.pcm);
},
async stop(context) {
  foxy.websocket.send(context.socket, JSON.stringify({type: "finish"}));
},
onMessage(event) {
  const packet = JSON.parse(event.message);
  if (packet.type === "partial") return {partial: packet.text};
  if (packet.type === "final") return {final: packet.text, ended: true};
  if (packet.type === "error") return {error: packet.message};
  return {};
}
```

Foxy 回调的等待上限分别是：`start` 10 秒、`audio` 3 秒、`onMessage` 5 秒、`stop` 35 秒。音频和入站帧队列有界，回调应保持短小。中州鹅的实际预算以[宿主接口](/maintainer/implementation/js-voice-plugins)为准。

## 工具 API

运行时提供：

- `foxy.crypto.hmacSha1Base64(secretField, message)`：返回 `Base64(HMAC-SHA1(message, secret))`，宿主读取密钥，不返回明文。
- `foxy.audio.wavBase64(pcmBase64, sampleRate)`：将单声道 16 位 PCM 包装为 WAV，不做重采样。
- `foxy.binary.utf8Base64(text)`、`base64Utf8(base64)`、`byteLength(base64)`、`readUInt8(base64, offset)`、`readInt32BE(base64, offset)`、`sliceBase64(base64, offset, length)`、`gunzipUtf8(base64)`：支持有界二进制协议。

## 安全与运行限制

- 主机必须是 `hosts` 中的精确条目，并单独获得用户批准。HTTP/SSE 可在运行期请求批准已声明主机；WebSocket 应先 `await foxy.websocket.authorize(...)`。未声明主机一律拒绝，端点设置不能扩大 Foxy 的访问范围。
- 密钥必须引用已声明的 `ui.secret` 字段，不应写入 URL、普通设置、额外请求头、源码或协议消息体。
- 敏感头、传输层控制的头以及含换行的头值会被拒绝。`Content-Type` 用作请求体媒体类型。
- 源码上限 256 KiB；每个 QuickJS 运行时内存上限 16 MiB。同步脚本执行上限 1.5 秒。网络 I/O 使用异步宿主 API。
- HTTP 请求体上限 1 MB、响应体上限 2 MB。WebSocket 帧和二进制工具输入同样有界。
- 更新只为仍声明的字段保留普通设置和密钥，字段含义不变时保持 ID 稳定。
- 回调抛异常或 Promise 被拒绝时，该操作失败。只捕获插件确实可以恢复的错误。

上述是 Foxy 参考限制；中州鹅 App HTTP 的动态配置域名规则、运行预算和密钥策略以[当前宿主接口](/maintainer/implementation/js-voice-plugins#js-app-test-branch)为准。

## 示例插件

以下链接指向本仓库的维护版本：

- [LLM 助手](https://github.com/SandyYuR/fcitx5-android/blob/fx-rime-only/app/src/main/assets/bundled/js-apps/llm-assistant.js)：声明式界面、多服务配置、密钥别名、编辑器预览与确认上屏。
- [通义千问识别](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/qwen-asr.js)：WebSocket 识别、有界单次请求、本地化设置。
- [豆包识别](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/doubao-asr.js)：V3 密钥握手、gzip 二进制帧；不支持将 V2 凭据写入请求体。
- [腾讯云识别](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/tencent-asr.js)：HMAC URL 签名、有界音频队列、服务端标点处理。
- [腾讯云翻译](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/tencent-translate.js)：签名 WebSocket 实时语音翻译。
