# JS 语音插件

语音识别提供方可以是一个 JavaScript 文件。它跑在应用内置的 QuickJS 运行时里，通过宿主提供的录音、WebSocket、HTTPS 与密钥接口工作，不需要另外安装 APK，也不需要编译。

[外部语音输入](./voice-input.md)讲的是 AIDL 提供方这条路；本页讲 JS 插件。两条路的会话管理、录音、静音判定和结果回填由同一套代码负责，区别只在「识别逻辑跑在哪里」。

## 选哪条路

| | JS 语音插件 | AIDL 提供方 |
|---|---|---|
| 载体 | 一个 `.js` 文件，在设置里导入 | 独立 APK，声明服务 |
| 改动成本 | 改脚本重导即可 | 重新编译、安装、跨进程调试 |
| 运行位置 | 输入法进程内的 QuickJS | 独立进程，走 Binder |
| 能拿到什么 | 宿主暴露的录音、WebSocket、HTTPS、密钥、二进制工具 | 完整 Android API |
| 适合 | 协议固定的云端识别、想快速试别的服务商 | 需要本地模型、系统 API 或自有 SDK |

需要本地推理、厂商 SDK 或别的系统能力时走 AIDL；只是把 PCM 按某家云服务的协议发出去、把文本取回来，JS 插件足够，而且不必为了换服务商装一个 APK。

## 使用

入口在**设置 → 便利功能 → 语音输入 → 语音插件设置**。这一页同时列出内置插件和导入的插件，按名称排序，当前在用的那条带「当前使用」标记。

### 内置插件

| 名称 | ID | 说明 |
|---|---|---|
| 通义千问语音 (JS) | `qwen-asr` | DashScope 实时流式或单次请求，需要 API key |
| 豆包语音 (JS) | `doubao-asr` | 火山引擎 bigmodel 二进制协议，需要 API key 或 access token + App ID |

选中一条进它的设置页填密钥与识别参数。密钥保存在本机并由宿主加密，脚本永远拿不到明文。

### 导入插件

1. 在语音插件设置页点「导入插件…」，选择一个注册了语音插件的 `.js` 文件（上限 256 KiB）。
2. 宿主会在 QuickJS 里真的跑一遍这个脚本，读出它的 ID、名称、版本、连接域名与设置项数量。
3. 确认对话框会逐条列出脚本想连的域名——这就是授权。同意才会保存。
4. 导入成功后，插件立刻出现在列表里，也出现在键盘语音入口的「首选语音输入」下拉里。

覆盖与移除：

- 导入的插件 ID 与已有导入插件相同即视为升级，替换脚本并把旧版本填过的设置和密钥保留下来。
- 导入的插件 ID 与内置插件相同会被拒绝，内置清单不允许被外部脚本顶替。
- 移除入口在被导入插件自己的设置页底部。移除后脚本文件与注册信息消失，但已填的设置与密钥仍留在本机；重新导入同名插件可继续使用。如果它正是当前首选语音输入，那一项会被清空并提示。

导入失败的常见原因：脚本没有调用 `foxy.plugin.register()`、没有声明 `voice.asr` 功能、ID 不合法或被内置插件占用、没有声明任何合法域名、体积超过 256 KiB、脚本本身有语法或运行时错误。

## 插件定义

一个插件就是一个 `foxy.plugin.register()` 调用：

```js
foxy.plugin.register({
  id: "example-asr",
  name: "Example ASR",
  version: "1.0.0",
  hosts: ["speech.example.com"],
  features: [{
    type: "voice.asr",
    id: "recognizer",
    label: "Example Speech Recognition",
    sampleRate: 16000
  }],
  configure(ui, locale) {},
  async start(context) {},
  async audio(context) {},
  async stop(context) {},
  onMessage(event) {}
});
```

- `id` 是稳定标识，须匹配 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`。改 ID 等于换插件，已存设置不再对应。
- `hosts` 是精确域名清单，不带协议、路径、端口或通配符。至少要有一个合法域名，运行期只允许连这里列出的域名。
- `features` 里要有一个 `type: "voice.asr"` 的条目。它的 `sampleRate` 决定宿主录音的采样率，会被夹到 8000–48000 Hz；不写按 16000 处理。宿主给的是单声道、16 位小端 PCM。
- 脚本要自包含：没有 `import`、`require`、浏览器 API，也没有任意网络访问，只能调用下面列出的宿主接口。

## 设置界面

`configure(ui, locale)` 在 QuickJS 里执行，用于声明设置项；`locale` 是当前语言标签，打开设置页时会重新执行，所以标签可以跟随语言变化。

```js
configure(ui) {
  ui.secret("apiKey", "API key", { description: "由宿主保存，不会传给脚本" });
  ui.select("mode", "模式", [
    { label: "流式", value: "streaming" },
    { label: "单次请求", value: "single" }
  ], "streaming");
  ui.text("endpoint", "端点", "wss://speech.example.com/v1", {
    visibleWhen: { key: "mode", value: "streaming" }
  });
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

`settings.description` 追加说明文字。`settings.visibleWhen: {key, value}` 在另一个设置项取指定值时显示；`settings.visibleWhen: {all: [{key, value}, ...]}` 要求全部满足。`settings.allowCustom: true` 允许用户在下拉框里自填值。select 的选项可以是字符串或 `{label, value}` 对象。

设置键是存取用的稳定 ID，不是显示文本。回调里拿到的普通值都是字符串，开关是 `"true"`/`"false"`。密钥字段由宿主编排成密码输入框，**密钥值不会出现在任何回调的 context 里**，插件只能通过字段名引用它。

## 生命周期

所有回调在同一个 QuickJS 会话里串行调用，可以是 `async` 并返回 Promise。

1. `start(context)`：每次识别会话调用一次，必须返回一个字符串 handle，之后以 `context.socket` 传回。非 WebSocket 模式返回任意哨兵字符串即可，例如 `"single"`。
2. `audio(context)`：每段 PCM 调用一次，`context.pcm` 是 Base64 编码的 PCM，另有 `sampleRate`、`taskId`、`socket`。流式插件直接转发；单次请求插件可以缓冲，但必须自己限制时长或字节数。
3. `onMessage({socket, message})`：文本帧到达时调用，`message` 是原始文本。二进制帧到达时改为 `onMessage({socket, binaryBase64})`，两个键不会同时出现。
4. `stop(context)`：用户结束识别、排队的音频都送完之后调用，`context` 含 `socket`、`sampleRate`、`taskId`。在这里发结束帧或提交缓冲音频。

所有回调的 `context` 还包含设置页里保存的全部普通设置值，以及数字形式的 `sampleRate` 和每次会话唯一的 `taskId`——`stop` 也能读到同一份设置，不用担心只在 `start` 里生效。

报告识别事件有两种等价写法：调用 `foxy.voice.ready()`、`partial(text)`、`final(text)`、`error(message)`、`ended()`，或者从回调返回动作对象：

```js
{ready: true}
{partial: "已识别的一段"}
{final: "完整结果"}
{error: "识别失败"}
{ended: true}
```

动作可以组合，例如 `{final: "你好", ended: true}`。区别在于 `{error: ...}` 或返回带 `error` 的动作用来让宿主立即中止会话；直接调 `foxy.voice.error()` 只是上报一个错误事件。`audio` 的返回值会被忽略，在 `audio` 里报告事件请用 `foxy.voice.*`。

## 宿主 API

插件只能调用宿主显式开放的接口，拿不到 `fetch`、原生 socket 或 Android API。

### WebSocket

- `foxy.websocket.connect(url, secretField, authHeader, authPrefix, headersJson)` 建立 `wss://` 连接，返回不透明 handle。密钥由宿主按 `secretField` 取出后注入请求头；`headersJson` 只放非敏感头。
- `await foxy.websocket.waitOpen(handle)` 等待连接就绪。
- `foxy.websocket.send(handle, text)` 发送文本帧。
- `foxy.websocket.sendBinary(handle, base64)` 发送解码后的二进制帧。
- `foxy.websocket.sendGzipFrame(handle, prefixBase64, payloadBase64)` 把 payload 压缩后发送 `prefix + 4 字节大端压缩长度 + 压缩数据` 的二进制帧。
- `foxy.websocket.close(handle)` 关闭连接。

认证头规则：

- 不传 `authHeader` 时用 `Authorization`，不传 `authPrefix` 时补 `Bearer `，即 `Authorization: Bearer <密钥>`。
- 传了头名时前缀就按传入值原样使用，空串表示**直接注入原始密钥**。火山引擎的 `X-Api-Key` 要的就是这种形式：`connect(url, "apiKey", "X-Api-Key", "")`。
- 密钥只按字段名从宿主取，不会进脚本；不要在 URL、脚本常量或普通设置项里写凭据。

### HTTPS JSON

`await foxy.http.post(url, secretField, jsonBody, headersJson)` 发一个 HTTPS POST，返回响应正文文本。认证固定为 `Authorization: Bearer <密钥>`，`jsonBody` 是请求体字符串，`headersJson` 是非敏感的附加头。重定向关闭，非 2xx 响应会抛错。

### 音频

`foxy.audio.wavBase64(pcmBase64, sampleRate)` 把 Base64 的单声道 16 位 PCM 包成 WAV，返回 Base64 WAV。它不重采样，也不校验服务商对格式的要求。

### 二进制工具

`foxy.binary` 提供 `utf8Base64(text)`、`base64Utf8(base64)`、`byteLength(base64)`、`readUInt8(base64, offset)`、`readInt32BE(base64, offset)`、`sliceBase64(base64, offset, length)`、`gunzipUtf8(base64)`，用于解析带长度前缀或压缩的二进制协议。

> 与 Foxy 上游文档的差异：本宿主**支持**二进制 WebSocket 帧（经 `onMessage({binaryBase64})` 交给插件），Foxy 当前文档写的是拒绝；`foxy.http.post` 没有自定义认证头参数，固定用 Bearer；本宿主也不提供 `foxy.capabilities` 别名，请直接用 `foxy.websocket`、`foxy.http`、`foxy.audio`、`foxy.binary`。

## 网络与安全

- 连接和请求只允许 `wss://` 与 `https://`，且主机名必须同时满足「脚本声明」和「用户导入时批准」。改一个端点设置也不会获得别的域名。
- 带 userinfo 的 URL（`https://user@host/`）一律拒绝，避免用凭据前缀伪造已批准域名。
- `headersJson` 里不能出现 `Host`、`Content-Length`、`Content-Type`、`Connection`、`Transfer-Encoding`、`Proxy-Authorization`、`Upgrade`、`Authorization` 这些由宿主或 OkHttp 管理的头；头值里的换行会被剔除，长度也有限制，防止请求头注入。
- `secretField` 必须是已声明的 `ui.secret` 字段。密钥由宿主在 Android Keystore 加密后保存，脚本只能给字段名，拿不到值。
- 会话开始前宿主会核对脚本声明的域名是否都在批准清单内；不一致就直接报错，避免访问在连接时才神秘失败。

## 运行时限制

- 脚本源码上限 256 KiB。
- 回调等待预算：`start` 10 秒、`audio` 3 秒、`onMessage` 5 秒、`stop` 35 秒。
- 结束识别后，宿主最多等 40 秒让插件把最终结果交回来，超时按会话结束处理。这比 AIDL 提供方的 3 秒宽松，因为「单次请求」模式的插件要先把整段音频 POST 上去再取回文本。
- 回调抛异常即视为该操作失败。能自愈就自己捕获，否则返回 `{error: message}`。
- 音频由宿主按会话排队并限流；插件跟不上时不会无限积压。

## 最小示例

这是一段传输骨架，不是某家服务商的协议——把开始/结束帧和响应解析替换成目标服务文档里的格式：

```js
foxy.plugin.register({
  id: "skeleton-asr",
  name: "Skeleton ASR",
  version: "1.0.0",
  hosts: ["speech.example.com"],
  features: [{ type: "voice.asr", id: "asr", label: "Skeleton", sampleRate: 16000 }],
  configure(ui) {
    ui.secret("apiKey", "API key");
    ui.text("endpoint", "Endpoint", "wss://speech.example.com/v1");
  },
  async start(context) {
    const socket = foxy.websocket.connect(context.endpoint, "apiKey");
    await foxy.websocket.waitOpen(socket);
    await foxy.websocket.send(socket, JSON.stringify({
      type: "start", sample_rate: context.sampleRate, task_id: context.taskId
    }));
    return socket;
  },
  async audio(context) {
    await foxy.websocket.sendBinary(context.socket, context.pcm);
  },
  async stop(context) {
    await foxy.websocket.send(context.socket, JSON.stringify({
      type: "finish", task_id: context.taskId
    }));
  },
  onMessage(event) {
    const packet = JSON.parse(event.message);
    if (packet.type === "ready") return { ready: true };
    if (packet.type === "partial") return { partial: packet.text };
    if (packet.type === "final") return { final: packet.text, ended: true };
    if (packet.type === "error") return { error: packet.message };
    return {};
  }
});
```

真实插件的写法可以参考仓库里的 `app/src/main/assets/bundled/js-voice/qwen-asr.js` 与 `doubao-asr.js`：一个走 JSON 文本帧，一个走二进制长度前缀 + gzip 帧。

## 英文原文

本页对应的上游指南见 [Foxy JavaScript Plugin Guide（英文原文）](./voice-input-js-en.md)。
