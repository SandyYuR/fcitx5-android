# JS 语音插件宿主与开发接口

本页描述中州鹅当前宿主。安装与账号配置请先看[JS 插件使用指南](/features/voice-input-js)；Foxy 英文原文只作接口来源参考，差异以本页及源码为准。

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
- `hosts` 是精确域名清单，不带协议、路径、端口或通配符。至少要有一个合法域名，运行期只允许连接这里列出且已经获得该插件用户授权的域名。
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

`settings.description` 追加说明文字。`settings.visibleWhen: {key, value}` 在另一个设置项取指定值时显示；`settings.visibleWhen: {all: [{key, value}, ...]}` 要求全部满足。`settings.defaultWhen: {key, values: {匹配值: 默认值}}` 可随另一字段变化选择联动默认值，例如豆包鉴权方式与资源 ID。`settings.allowCustom: true` 允许用户在下拉框里自填值。select 的选项可以是字符串或 `{label, value}` 对象。

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

动作可以组合，例如 `{final: "你好", ended: true}`。回调返回的动作对象和 `foxy.voice.*` 汇总到同一批事件中处理；`audio` 返回的动作对象也会处理。返回 `error` 或调用 `foxy.voice.error()` 都会结束当前会话。`start` 的返回值仍应是句柄字符串，启动阶段报告状态时使用 `foxy.voice.*`。

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

### HMAC 签名

`foxy.crypto.hmacSha1Base64(secretField, text)` 使用已声明的 `ui.secret` 字段在宿主执行 HMAC-SHA1，返回 Base64 签名。脚本只能得到签名，不能读取密钥。
内置腾讯云识别与翻译插件使用此接口；普通 AppID、SecretId 与作为密钥的 SecretKey 必须按对应字段保存。

### 音频

`foxy.audio.wavBase64(pcmBase64, sampleRate)` 把 Base64 的单声道 16 位 PCM 包成 WAV，返回 Base64 WAV。它不重采样，也不校验服务商对格式的要求。

### 二进制工具

`foxy.binary` 提供 `concatBase64(chunks)`（先解码再拼接，不能直接连接带填充的 Base64 字符串）、`utf8Base64(text)`、`base64Utf8(base64)`、`byteLength(base64)`、`readUInt8(base64, offset)`、`readInt32BE(base64, offset)`、`sliceBase64(base64, offset, length)`、`gunzipUtf8(base64)`，用于解析带长度前缀或压缩的二进制协议。

> 与 Foxy 上游文档的差异：本宿主**支持**二进制 WebSocket 帧（经 `onMessage({binaryBase64})` 交给插件），Foxy 当前文档写的是拒绝；`foxy.http.post` 没有自定义认证头参数，固定用 Bearer；本宿主也不提供 `foxy.capabilities` 别名，请直接用 `foxy.websocket`、`foxy.http`、`foxy.audio`、`foxy.binary`。

## 网络与安全

- 连接和请求只允许 `wss://` 与 `https://`，且主机名必须同时满足「脚本声明」和「用户明确批准」。改一个端点设置也不会获得别的域名。
- 授权按插件分别保存，不会因一个插件已获准就顺带批准其他插件。插件设置页可以查看完整域名清单，并撤销该插件的授权。
- 带 userinfo 的 URL（`https://user@host/`）一律拒绝，避免用凭据前缀伪造已批准域名。
- `headersJson` 里不能出现 `Host`、`Content-Length`、`Content-Type`、`Connection`、`Transfer-Encoding`、`Proxy-Authorization`、`Upgrade`、`Authorization` 这些由宿主或 OkHttp 管理的头；头值里的换行会被剔除，长度也有限制，防止请求头注入。
- `secretField` 必须是已声明的 `ui.secret` 字段。密钥由宿主在 Android Keystore 加密后保存，脚本只能给字段名，拿不到值。
- 会话开始前宿主会核对脚本声明的域名是否都在批准清单内；不一致就直接报错，避免访问在连接时才神秘失败。

完整数据备份会包含自行导入的插件、插件普通设置和授权记录。`ui.secret` 保存的密钥在备份中仍是密文；Android Keystore 的解密密钥不会随备份导出，因此换设备、卸载重装或清除应用数据后，需要重新填写密钥。不要把密钥写进脚本源码或声明成 `ui.text` 普通设置。

## 运行时限制

| 资源 | 当前上限 |
| --- | --- |
| 脚本源码 | 256 KiB |
| QuickJS 堆 | 32 MiB |
| 顶层初始化 | 2 秒 |
| 二进制解码或音频拼接 | 4 MiB |
| HTTP 响应或解压结果 | 2 MiB |
| WebSocket 单条消息 | 1 MiB |
| HTTP 请求体 | 8 MiB |
| 同一会话的 WebSocket | 最多 4 条 |

这些预算分别约束脚本、宿主缓冲和传输，不能相互替代。

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

本页对应的上游指南见 [Foxy JavaScript Plugin Guide（英文原文）](/features/voice-input-js-en)。


## 宿主维护与验证

生产实现位于 `input/voice/js/`。`JsVoicePrelude` 声明 JavaScript 全局接口，`JsVoiceNatives` 提供真实网络与签名；设置页与导入求值使用同文件的 `JsVoiceStubNatives`，不执行网络和真实签名。
新增 API 必须同时落到 prelude、真实实现与 stub，再让 `JsVoiceBridgeContractTest` 加载真实 bundled 脚本验证。只修改运行中的实现，会让设置页在求值时出现 `ReferenceError`。

提供方以 `js:` 前缀注册到共用 `VoiceInputProviderRegistry`，复用 `VoiceInputProviderManager` 的会话、录音和回填。取消与旧会话回调必须失效，不能另造一套输入结果队列。

运行目标 JVM 测试 `--tests 'org.fcitx.fcitx5.android.input.voice.*'`，再构建 Debug。测试通过不能替代真实云端服务联调；服务商鉴权与识别效果需在配置后单独确认。
