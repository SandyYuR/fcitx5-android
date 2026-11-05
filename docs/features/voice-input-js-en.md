---
docKind: reference
---

# Foxy JavaScript Plugin Reference

::: info Source and compatibility
This is the updated **Foxy JavaScript Plugin Reference** supplied as `js-plugin-README.md`, replacing the older voice-only guide.
It describes Foxy's API, rather than a promise that every API is implemented in 中州鹅.

The AI assistant and JS App support described below for 中州鹅 currently belong to the `test/ai-native-protocols` test branch.
Use its APK when testing these features. See the [current host implementation](/maintainer/implementation/js-voice-plugins#js-app-test-branch) and [installation and AI workflow](/features/voice-input-js#ai-assistant).

| Area | 中州鹅 test branch |
| --- | --- |
| JS Apps | App registration, permissions, structured native layouts, editor APIs, credentials and non-streaming HTTP are supported. The toolbar provides an AI assistant entry. |
| Network authorization | App HTTP accepts declared hosts or hosts derived from configured HTTPS addresses, with separate approval before sending text or credentials. The built-in assistant has no default API addresses or static hosts. Voice transports still require declared hosts. |
| Streaming and commands | App SSE/WebSocket, legacy flat layouts, generic `js.*` layout commands, arbitrary App selection and `foxy.websocket.authorize()` are not implemented. The voice WebSocket transport is supported. |
| Controls | Floating panels keep keyboard input available. The test branch additionally supports `input.readOnly` for a selectable AI reply. |

Button IDs and selector IDs are delivered to the plugin as events: a label such as “Confirm” has no built-in generate/insert behavior. Handle selector events explicitly to keep the panel open.
The maintained assistant shows **Send to AI** before generation and **Insert** after a reply is ready.
The example links at the end point to this repository's adaptations.
:::

[中文版](/features/voice-input-js-reference-zh-CN) · <a href="/fcitx5-android/reference/js-plugin-README.md" download>Download the supplied English README</a>


Foxy imports one self-contained JavaScript source file from **Settings > JavaScript plugins**. Files under `plugins/` are source examples and are not bundled into the APK. Sharing a JavaScript file to Foxy also installs it. A plugin that declares network hosts asks the user to approve them during import.

The runtime has two executable integrations:

- `voice.asr`: a streaming or single-request voice recognition engine.
- `foxy.app.register(...)`: a foreground app capability invoked from a keyboard layout or toolbar.

Other feature metadata is preserved but ignored unless Foxy provides a consumer. A settings-only plugin is valid.

## Plugin Definition

Register a plugin once with `foxy.plugin.register()`:

```javascript
foxy.plugin.register({
  id: "example-plugin",
  name: "Example Plugin",
  version: "1.0.0",
  hosts: ["api.example.com"],
  features: [],
  configure(ui, locale) {
    ui.secret("apiKey", "API key");
  }
});
```

`id` must match `[a-z0-9][a-z0-9._-]{0,63}`. Keep it stable between updates so Foxy can retain settings and credentials with unchanged field IDs. `name` and `version` are display metadata. Feature IDs use the same identifier form; feature types use lowercase letters, digits, dots, and hyphens.

`hosts` contains exact hostnames only, without scheme, path, port, wildcards, or subdomain patterns. Use an empty list for plugins that do not need network access.

A host must be declared in `hosts`; declaration alone does not grant access. On first HTTP or SSE request to a declared-but-unapproved host, Foxy shows a confirmation prompt and persists the approval if the user allows it. A host that was never declared can never be approved at runtime. For WebSocket, whose `connect` is synchronous, call `await foxy.websocket.authorize("wss://api.example.com/...")` or `await foxy.websocket.authorize("api.example.com")` before connecting to the first unapproved host; it shows the same prompt.

## Settings

`configure(ui, locale)` declares native settings controls. `locale` is the current BCP-47 language tag, and `configure` runs again when the plugin settings are opened.

```javascript
configure(ui) {
  ui.text("endpoint", "Endpoint", "https://api.example.com/v1");
  ui.secret("apiKey", "API key", {
    description: "Stored by Foxy; the secret value is not passed to JavaScript."
  });
  ui.select("model", "Model", [
    {label: "Fast", value: "fast-model"},
    {label: "Accurate", value: "accurate-model"}
  ], "fast-model");
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

Select options may be strings or `{label, value}` objects. `allowCustom: true` permits a custom value. `settings.description` adds supporting text. `settings.visibleWhen` supports `{key, value}` or `{all: [{key, value}, ...]}`. Ordinary values are passed as strings; switches are `"true"` or `"false"`. Secret values are encrypted by Foxy and never included in plugin callback context.

## App Capabilities

Each plugin can register one or more foreground apps. The App ID becomes its command: ID `llm_assistant` registers `js.llm_assistant`. IDs are global across installed plugins, so choose distinct IDs.

```javascript
foxy.app.register({
  id: "rewrite",
  label: "Rewrite",
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
            {type: "text", text: "Rewrite", size: 14, bold: true},
            {type: "input", id: "input", label: "Text", multiline: true, value: selection, height: 72},
            {type: "row", align: "end", children: [
              {type: "button", id: "close", label: "Close"},
              {type: "button", id: "send", label: "Send", style: "filled"}
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

Invoke it from a layout:

```json
{"type":"app","command":"js.rewrite"}
```

The same command can be selected in the toolbar action editor. Every App receives:

- `context.event`: `"open"` or a button ID from the last dialog.
- `context.values`: current input/select values for button events.
- `context.appId` and `context.command`.
- `context.settings`: ordinary saved plugin settings.
- Top-level aliases for ordinary settings, for compatibility.
- `context.configuredSecrets`: configured secret field IDs only when `credentials.list` is granted. It never contains secret values.

The handler may return a dialog description, `{dialog: spec}`, or `{close: true}`. Button callbacks invoke `onOpen` again with the new event and values. A host dismissal cancels the runtime and clears any temporary editor composition.

### Declarative Layout Nodes

The plugin owns the layout tree. Foxy renders these nodes with native controls styled to the current keyboard theme:

| Node | Fields |
| --- | --- |
| `column`, `row` | `children`, `gap`, `align` (`start`, `center`, `end`); each child may have `weight` |
| `text` | `text`, `size`, `muted`, `bold` |
| `input` | `id`, `label`, `labelPlacement`, `placeholder`, `multiline`, `height`, `value`, `weight` |
| `select` | `id`, `label`, `labelPlacement`, `options`, `value`, `height`, `weight` |
| `button` | `id`, `label`, `style` (`outlined` or `filled`), `weight` |
| `spacer` | `height`, `weight` |

Select `options` may be strings or `{label, value}` objects. Labels are inline by default; set `labelPlacement: "top"` for stacked labels. Children do not stretch unless `weight` is provided. Dialog-level `maxWidth` and `maxHeight` are dp bounds. The old flat `fields` / `buttons` format remains supported for compatibility.

### Incremental Dialog Updates

For streaming output or progressive results, mutate controls without rebuilding the layout:

```javascript
await foxy.ui.dialog.setLoading(true);
await foxy.ui.dialog.setValue("result", "partial text");
await foxy.ui.dialog.setEnabled("send", false);
await foxy.ui.dialog.setError("temporary error");
await foxy.ui.dialog.update(nextDialogSpec);
await foxy.ui.dialog.close();
```

`setValue` accepts input/select IDs. `setError` displays a bounded error line in the current dialog. These methods are asynchronous host calls; await them when ordering matters.

## Host Permissions

On first App use, Foxy asks the user to grant the App's declared permissions. Grants can be revoked in the plugin settings menu. `ui.dialog` is implicitly included for every registered App.

| Permission | APIs |
| --- | --- |
| `editor.read_selection` | `foxy.editor.getSelection()` |
| `editor.read_text` | `foxy.editor.getText()` |
| `editor.composition` | `foxy.editor.setComposition(text)`, `foxy.editor.clearComposition()` |
| `editor.commit` | `foxy.editor.commit(text)` |
| `network.request` | HTTP, SSE, and WebSocket APIs |
| `credentials.list` | `foxy.credentials.list()` |

The editor APIs operate on the active application editor. `getText()` returns at most 64 KiB. `setComposition()` shows temporary composing text in the host app; `commit()` clears that composition and commits the supplied text; `clearComposition()` removes the temporary composition.

## Credentials

Declare each secret with `ui.secret`. Use its field ID as the network `credential` alias. Foxy injects the saved value into the request and never returns the secret to JavaScript.

```javascript
const aliases = foxy.credentials.list();
// Example: ["deepseekKey", "weatherApiKey"]
```

`credentials.list()` requires `credentials.list` permission and returns only configured secret IDs, not values. Without the permission, `context.configuredSecrets` is empty.

## HTTP Requests

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

Supported methods are `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`. `credential` is optional; when set, the secret must be a declared and configured secret field. `Content-Type` is interpreted as the request body media type; other transport-controlled or sensitive headers are rejected. Non-2xx responses return `ok: false` and the response body. Redirects are disabled.

The legacy `foxy.http.post(url, secretField, body, headersJson = "{}")` remains available to existing voice plugins. It always sends a JSON POST with Bearer authentication and rejects non-2xx responses.

## Streaming HTTP / SSE

`foxy.http.stream(options)` returns a stream ID immediately. Each SSE event is delivered as `(streamId, event, data)`; non-SSE responses are delivered one line at a time with event `"line"`.

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

// Cancel from a button or app close handler.
foxy.http.cancelStream(streamId);
```

SSE `event:` and `data:` fields are parsed; multiple `data:` lines are joined with newline. The stream is HTTPS-only, uses the same host approval and `network.request` permission as ordinary HTTP, and is cancelled when the plugin App closes. Chunk callbacks are awaited sequentially to provide backpressure.

## WebSocket

Ordinary Apps and voice plugins can use the same host WebSocket transport:

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

`connect(url, secretField, authHeader = "Authorization", authPrefix = "Bearer ", headersJson = "{}")` returns an opaque socket ID. Pass an empty secret field for a pre-signed URL. `sendGzipFrame(socketId, prefixBase64, payloadBase64)` sends `prefix + 4-byte big-endian compressed length + gzip(payload)` as a binary frame. Binary frame size is bounded. The connection uses WSS, no redirects, the plugin's declared/approved host list, and `network.request` permission.

WebSocket handlers are registered once per JS runtime. `onClose` receives `expected = true` for a plugin-requested close.

## Editor APIs

```javascript
const selection = await foxy.editor.getSelection();
const documentText = await foxy.editor.getText();
await foxy.editor.setComposition("temporary result");
await foxy.editor.commit("inserted result");
await foxy.editor.clearComposition();
```

`getSelection()` and `getText()` require their respective read permissions. `setComposition` / `clearComposition` require `editor.composition`; `commit` requires `editor.commit`. Text inputs are bounded by the host.

## Voice ASR Feature

`voice.asr` is a separate feature from `js.*` Apps. Declare it in `features`; `sampleRate` is clamped to 8,000-48,000 Hz. Foxy supplies mono signed 16-bit little-endian PCM at that rate. Keep plugin files self-contained: imports, `require`, browser APIs, and arbitrary network APIs are not available.

```javascript
foxy.plugin.register({
  id: "example-asr",
  name: "Example ASR",
  version: "1.0.0",
  hosts: ["speech.example.com"],
  features: [{type: "voice.asr", id: "recognizer", label: "Example ASR", sampleRate: 16000}],
  configure(ui) { ui.secret("apiKey", "API key"); },
  async start(context) {},
  async audio(context) {},
  async stop(context) {},
  onMessage(event) {}
});
```

Foxy invokes plugin callbacks serially in one QuickJS session; methods may be async:

1. `start(context)` runs once per recognition session. Context includes ordinary settings, `sampleRate`, and unique `taskId`. Return a string handle; it is passed as `context.socket` to later callbacks. A non-network engine can return a sentinel such as `"single"`.
2. `audio(context)` runs for each PCM chunk. Context includes Base64 `pcm`, `sampleRate`, `taskId`, and `socket`. Streaming engines forward each chunk; single-request engines may buffer only with a strict size/duration limit.
3. `onMessage({socket, message})` handles WebSocket text frames; binary frames arrive as `{socket, binaryBase64}`.
4. `stop(context)` runs after queued audio is delivered. Send the provider finish frame or submit buffered audio.

Callbacks can return actions:

```javascript
{ready: true}
{partial: "recognized so far"}
{final: "completed phrase"}
{error: "recognition failed"}
{ended: true}
```

Actions can be combined, such as `{final: "hello", ended: true}`. The same events can be emitted through `foxy.voice.ready()`, `partial(text)`, `final(text)`, `error(message)`, and `ended()`. Returning `{error: message}` aborts the session; returning `{ended: true}` finishes it.

The following is a transport skeleton; replace provider frames and response parsing with the provider's documented protocol:

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

Voice callback wait limits are 10 seconds for `start`, 3 seconds for `audio`, 5 seconds for `onMessage`, and 35 seconds for `stop`. Audio and incoming frame queues are bounded. Keep callback work short and bounded.

## Utility APIs

These namespaces are provided to plugin runtimes that need them:

- `foxy.crypto.hmacSha1Base64(secretField, message)` returns Base64(HMAC-SHA1(message, secret)). The secret is read natively and never returned.
- `foxy.audio.wavBase64(pcmBase64, sampleRate)` wraps mono 16-bit PCM in a WAV container; it does not resample.
- `foxy.binary.utf8Base64(text)`, `base64Utf8(base64)`, `byteLength(base64)`, `readUInt8(base64, offset)`, `readInt32BE(base64, offset)`, `sliceBase64(base64, offset, length)`, and `gunzipUtf8(base64)` support bounded binary protocols.

## Security And Runtime Limits

- Hostnames must be exact entries in `hosts` and separately approved by the user. HTTP/SSE can request approval for a declared host at runtime; WebSocket callers should `await foxy.websocket.authorize(...)` first. Undeclared hosts are always rejected. Endpoint settings cannot grant access to another host.
- Credentials must reference a declared `ui.secret` field. Do not put secrets in URLs, ordinary settings, headers, plugin source, or protocol message bodies.
- Sensitive headers, transport-owned headers, and header values containing line breaks are rejected. `Content-Type` is interpreted as the request-body media type.
- Plugin source is limited to 256 KiB; each QuickJS runtime has a 16 MiB memory limit. Synchronous script evaluation is limited to 1.5 seconds. Use async host APIs for network I/O.
- HTTP request bodies are limited to 1 MB and responses to 2 MB. WebSocket frames and binary helper inputs are bounded.
- Plugin updates retain ordinary settings and secrets only for still-declared fields. Keep setting IDs stable when meanings do not change.
- Throwing/rejecting from a callback fails that operation. Catch only errors the plugin can recover from.

## Example Plugins

- [LLM Assistant](https://github.com/SandyYuR/fcitx5-android/blob/test/ai-native-protocols/app/src/main/assets/bundled/js-apps/llm-assistant.js): plugin-owned declarative UI, multiple provider settings, API credentials, editor composition, confirm, and clear.
- [Qwen ASR](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/qwen-asr.js): WebSocket recognition, bounded single-request mode, provider response parsing, localized settings.
- [Doubao ASR](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/doubao-asr.js): V3 secret-backed handshake and gzip binary frames. Legacy V2 credentials in request bodies are intentionally unsupported.
- [Tencent ASR](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/tencent-asr.js): URL signing via `foxy.crypto`, bounded audio backlog, server-side punctuation filtering.
- [Tencent Translate](https://github.com/SandyYuR/fcitx5-android/blob/7cb141aa1e9ebf110e6a8a7e4059fb2095209989/app/src/main/assets/bundled/js-voice/tencent-translate.js): real-time speech translation using a signed WebSocket endpoint.
