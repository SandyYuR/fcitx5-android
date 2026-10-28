> 本页为上游 [Foxy](https://github.com/fxliang/foxy) 项目的 *Foxy JavaScript Plugin Guide* 原文，用于对照插件接口的完整语义。
> 中文导读与中州鹅的实现差异见 [JS 语音插件（中文）](./voice-input-js.md)。
> 本应用中「设置 > JavaScript plugins」对应**设置 → 便利功能 → 语音输入 → 语音插件设置**；示例插件文件随 APK 内置，不需要单独下载。

# Foxy JavaScript Plugin Guide

Foxy imports a plugin as one JavaScript source file from **Settings > JavaScript plugins**. Plugin files in this directory are examples and are not bundled into the APK. Keep each source under a directory named for its plugin ID while retaining a descriptive filename, for example `qwen-asr/qwen-asr.js`.

The runtime currently has one executable feature provider: `voice.asr`. Other feature types may be declared and preserved, but Foxy will ignore them until the app adds a provider and a consumer for that type. A settings-only plugin is also valid.

## Plugin Definition

Register one object with `foxy.plugin.register()`:

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

`id` is a stable identifier matching `[a-z0-9][a-z0-9._-]{0,63}`. Keep it unchanged between updates so Foxy can retain settings whose keys remain declared. `name` and `version` are display metadata. `features` can be omitted or empty. Feature IDs use the same identifier form; feature types use lowercase letters, digits, dots, and hyphens.

For ASR, declare a `voice.asr` feature. `sampleRate` is read from its metadata and clamped to 8,000-48,000 Hz. The host supplies mono, signed 16-bit little-endian PCM at that rate. Keep the JS file self-contained: imports, `require`, browser APIs, and arbitrary network APIs are not provided.

## Settings UI

`configure(ui, locale)` runs in QuickJS and declares native controls. `locale` is the current app BCP-47 language tag; the function runs again when settings are opened, so labels can follow an app language change.

```js
configure(ui) {
  ui.secret("apiKey", "API key", {
    description: "Stored by Foxy; never passed to JavaScript."
  });
  ui.select("mode", "Mode", [
    {label: "Streaming", value: "streaming"},
    {label: "Single request", value: "single"}
  ], "streaming");
  ui.text("endpoint", "Endpoint", "wss://speech.example.com/v1", {
    visibleWhen: {key: "mode", value: "streaming"}
  });
  ui.switch("partials", "Partial results", true);
  ui.number("timeout", "Timeout", "30");
}
```

Available declarations:

- `ui.text(key, label, defaultValue = "", settings = {})`
- `ui.secret(key, label, settings = {})`
- `ui.select(key, label, options, defaultValue = "", settings = {})`
- `ui.switch(key, label, defaultValue = false, settings = {})`
- `ui.number(key, label, defaultValue = "", settings = {})`

Select options may be strings or `{label, value}` objects. `allowCustom: true` lets the user enter a custom select value. `settings.description` adds supporting text. `settings.visibleWhen: {key, value}` shows a field when one other setting matches; `settings.visibleWhen: {all: [{key, value}, ...]}` requires every condition to match.

Setting keys are stable storage IDs, not translated labels. Ordinary setting values are passed to callbacks as strings; switches are `"true"` or `"false"`. Secret values are encrypted by Foxy and are never included in callback contexts. A secret field declaration creates a host-rendered password input; Foxy provides its clear-key action.

## ASR Lifecycle

Foxy invokes plugin methods serially in one QuickJS session. Methods may be `async` and return Promises.

1. `start(context)` is called once per recognition session. Context includes saved ordinary settings, `sampleRate`, and a unique `taskId`. It must return a string handle. The handle is passed back as `context.socket` to later callbacks. For a non-WebSocket mode, return a sentinel string such as `"single"`.
2. `audio(context)` is called for each PCM chunk. Context includes `pcm` as Base64 PCM, `sampleRate`, `taskId`, and `socket`. Streaming plugins normally forward each chunk; single-request plugins may buffer audio, but must impose a strict duration/byte limit.
3. `onMessage({socket, message})` is called for each incoming WebSocket text frame. `message` is the original text. Binary WebSocket responses are currently rejected.
4. `stop(context)` is called after queued audio has been delivered when the user ends recognition. Context includes `socket`, `sampleRate`, and `taskId`. Send the service's finish message or submit buffered audio here.

`start` can return `{ready: true}` only as a result action, but still must return its string handle. For non-network modes, call `foxy.voice.ready()` before returning the sentinel. A callback may return an action object:

```js
{ready: true}
{partial: "recognized so far"}
{final: "completed phrase"}
{error: "recognition failed"}
{ended: true}
```

Actions can be combined, for example `{final: "hello", ended: true}`. The same events can be emitted directly with `foxy.voice.ready()`, `partial(text)`, `final(text)`, `error(message)`, and `ended()`. Returning `{error: message}` from `onMessage` or `stop` aborts the session; returning `{ended: true}` lets Foxy finish and clean it up. Direct `foxy.voice.error()` reports an error event, so use the returned action when the plugin needs the host to abort immediately.

The following is a transport skeleton, not a provider protocol. Replace the example start/finish frames and response parser with the target service's documented protocol:

```js
async start(context) {
  const socket = foxy.websocket.connect(context.endpoint, "apiKey");
  await foxy.websocket.waitOpen(socket);
  await foxy.websocket.send(socket, JSON.stringify({
    type: "start",
    sample_rate: context.sampleRate,
    task_id: context.taskId
  }));
  return socket;
},
async audio(context) {
  await foxy.websocket.sendBinary(context.socket, context.pcm);
},
async stop(context) {
  await foxy.websocket.send(context.socket, JSON.stringify({
    type: "finish",
    task_id: context.taskId
  }));
},
onMessage(event) {
  const packet = JSON.parse(event.message);
  if (packet.type === "ready") return {ready: true};
  if (packet.type === "partial") return {partial: packet.text};
  if (packet.type === "final") return {final: packet.text, ended: true};
  if (packet.type === "error") return {error: packet.message};
  return {};
}
```

The start callback must return its string handle; callback action objects are handled from `onMessage` and `stop`. `audio` return values are ignored, so report events with `foxy.voice.*` there if needed.

## Host APIs

Only APIs explicitly exposed by Foxy are available; plugins do not get `fetch`, native sockets, or direct Android access. The same built-in namespaces are also available under `foxy.capabilities`.

### WebSocket

- `foxy.websocket.connect(url, secretKeyField, authHeaderName = "Authorization", authPrefix = "Bearer ", headersJson = "{}")` opens a `wss://` connection and returns an opaque string handle. Foxy injects the secret in the selected header; `headersJson` is for non-sensitive headers.
- `await foxy.websocket.waitOpen(handle)` waits for the connection to open.
- `foxy.websocket.send(handle, text)` sends a text frame.
- `foxy.websocket.sendBinary(handle, base64)` sends decoded bytes.
- `foxy.websocket.sendGzipFrame(handle, prefixBase64, payloadBase64)` gzip-compresses the payload and sends `prefix + 4-byte big-endian compressed length + compressed payload` as a binary frame.
- `foxy.websocket.close(handle)` closes a connection.

The custom authentication header name and prefix let a plugin use provider-specific header schemes without exposing the secret. Foxy rejects transport-owned headers, credentials in `headersJson`, and header values containing line breaks. Do not put credentials in URLs, JavaScript, or ordinary settings.

WebSocket text frames arrive at `onMessage({socket, message})`; binary frames arrive as `onMessage({socket, binaryBase64})`. `foxy.binary` provides `utf8Base64(text)`, `base64Utf8(base64)`, `byteLength(base64)`, `readUInt8(base64, offset)`, `readInt32BE(base64, offset)`, `sliceBase64(base64, offset, length)`, and `gunzipUtf8(base64)` for parsing length-framed compressed protocols. Binary inputs and decompressed payloads are bounded by the host. Incoming message queues are bounded too; a plugin that cannot keep up fails the session rather than retaining unbounded frames.

### HTTPS JSON

`await foxy.http.post(url, secretKeyField, jsonBody, headersJson = "{}")` makes an HTTPS POST and returns the response body as a string. Foxy adds the same Bearer authorization header. Additional headers are JSON string values; `Authorization`, `Host`, `Content-Length`, `Content-Type`, and `Connection` are reserved. Redirects are disabled. Non-2xx responses reject with an error.

### Audio

`foxy.audio.wavBase64(pcmBase64, sampleRate)` wraps Base64 mono 16-bit PCM in a WAV container and returns Base64 WAV. It does not resample audio or validate the provider's format requirements.

## Network Permissions and Security

Declare exact hostnames in `hosts`, without scheme, path, port, wildcard, or subdomain pattern. For example, `speech.example.com`. Foxy asks the user to approve the declared hosts during import. Calls are allowed only when the URL host is both declared and approved, and must use `wss://` or `https://` as required by the API. Changing an endpoint setting cannot grant access to another host. Reimporting an update replaces the grants with the hosts explicitly approved for that import.

`secretKeyField` must name a field currently declared with `ui.secret`. The secret is read and attached by the native host; JavaScript never receives its value. This supports providers that authenticate with a secret-backed handshake header, including a custom API-key header. Static `headersJson` cannot set authentication, cookie, token, or secret headers. Secret insertion into arbitrary message bodies is intentionally not exposed. Users can inspect the host list before granting it, so declare only hosts required by the plugin.

## Runtime Limits and Errors

- Source files are limited to 256 KiB; each QuickJS runtime has a 16 MiB memory limit.
- Synchronous JavaScript evaluation is limited to 1.5 seconds. Keep parsing and audio work bounded; use asynchronous host calls for network I/O.
- WebSocket open wait is limited to 10 seconds. Host callback waits are bounded: `start` 10 seconds, `audio` 3 seconds, `onMessage` 5 seconds, and `stop` 35 seconds.
- The host audio queue is bounded. If audio callbacks fall behind, the session fails rather than growing without limit.
- Throwing or rejecting from a callback fails the corresponding operation. Catch errors only when the plugin can recover; otherwise return `{error: message}` or call `foxy.voice.error(message)`.
- Plugin updates retain ordinary values only for still-declared non-secret fields, retain secrets only for still-declared secret fields, and replace network approvals. Keep setting keys stable when their meaning is unchanged.

## Provider-Specific Notes

An ASR provider protocol must define its own start/finish messages, audio framing, and transcript parser. Foxy supplies PCM, secret-backed custom authentication headers, binary WebSocket callbacks, and a length-prefixed gzip frame helper; it does not translate provider protocols. Frame-specific prefix bytes and response parsing remain in the plugin JavaScript.

The generic transport additions cover header-authenticated binary WebSocket protocols such as the V3 style. They do not provide a secure way to interpolate secrets into protocol message bodies, so providers requiring that (including the legacy Doubao V2 credential payload) are outside this interface.

## Differences in this app

中州鹅的宿主实现与本文有几处实际差别，接入时以中文页为准：

- Binary WebSocket frames are supported and delivered as `onMessage({socket, binaryBase64})`; the paragraph above saying binary responses are rejected does not apply here.
- `foxy.http.post` always authenticates with `Authorization: Bearer <secret>`; it takes no custom header name or prefix.
- `foxy.capabilities` alias is not provided; use `foxy.websocket`, `foxy.http`, `foxy.audio`, and `foxy.binary` directly.
- A plugin must declare at least one valid host and a `voice.asr` feature to be importable; settings-only plugins are rejected at import.
- Plugin IDs of the built-in plugins (`qwen-asr`, `doubao-asr`) are reserved for imported scripts.
- The finish watchdog after `endStream` is 40 seconds for JS plugins, so single-request providers that POST the whole clip still get their result back.
- Callback wait budgets match the numbers above: start 10 s, audio 3 s, onMessage 5 s, stop 35 s.
