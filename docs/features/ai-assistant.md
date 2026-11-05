# AI 文本助手（测试分支）

::: info 对应版本
本页对应 `test/ai-native-protocols` 测试分支的内置助手 `1.23.1-fx`，不代表默认分支和旧 APK 已包含。
本次宿主界面与事件分发核对以用户提供的 Foxy `20f1709` APK 为准；该 APK 未内置 LLM 示例脚本，生成、回复及上屏由内置 JS 的状态处理完成。
:::

在「设置 → 键盘 → 工具栏 → 编辑按钮」中，点击 **Kawaii Bar（顶行）的「＋」→ AI 助手 → 保存**。
也可以从「可选按钮」区点击 AI 助手，选择加入 Kawaii Bar 或 Status Area（状态区）。
动作 ID 为 `ai_assistant`，设置页和运行时共用同一份可配置动作目录。
默认布局不被改动。短按打开，长按进入助手设置。默认图标是 Google Material Symbols
的 `auto_awesome`（Outlined / 24px，Apache-2.0），支持 `toolbar.ai_assistant` 图标主题槽。

## 配置和使用

1. 打开「设置 → 便捷功能 → JS 插件设置 → AI 助手」，也可长按 AI 按钮。
   「设置 → 输入与候选 → 语音输入 → JS 插件设置」仍保留，两个入口打开同一页面。
2. 选择一个服务配置项，填写 HTTPS 基础地址、模型和 API 密钥，并选择端点支持的 API 协议。
   DeepSeek、OpenAI、通义千问、Anthropic / Claude 和自定义项分别保存设置，所有地址默认留空。
   已经明确保存的地址、模型和密钥继续保留；此前依赖内置默认地址的配置需要自行补填。
3. 可以填写 `https://example.com/v1`，也可填写完整的
   `https://example.com/v1/chat/completions`、`https://example.com/v1/messages` 或
   `https://example.com/v1/responses`。切换协议会替换已知接口后缀，保留中转站路径前缀和查询参数。
   基础地址需要 `/v1` 时请自行包含它。无需修改或重新导入 JS 中的域名列表。
4. 在应用中选中文本后打开助手；未选中时可直接在助手文本框输入或粘贴内容。
5. 选择用途后点击 **发送 AI**，包括「火星文」在内的用途均通过当前服务处理。生成前不显示“确认上屏”，空输入会提示填写并保留窗口。
6. 返回后在面板的只读 **AI 回复** 框中阅读、滚动或长按复制，同时在原应用编辑框中临时预览。此时出现 **确认上屏**：点击后才正式提交回复并关闭助手；“重新生成”则重新处理当前问题。
7. 修改问题、用途、服务或配置后，旧回复失效。即使收到旧式 `confirm` 事件，没有当前问题对应的有效回复时也按生成处理，不会直接关闭或提交旧回复。
8. 请求失败保留问题和面板，允许重试。清空、关闭或系统返回撤销预览并恢复原选中文本；输入会话结束时取消未完成请求。

助手以**可拖动的浮动面板**打开，初始位于键盘上方。拖动标题或空白处可移动，位置受屏幕和键盘边界约束；
切换用途或服务时保留拖动位置。面板没有背景遮罩，不抢走原输入会话的焦点，面板外的键盘可照常点击。
在助手文本框中放置光标后，输入法输入、退格、换行和组合输入会进入该文本框；发送将问题交给 AI；生成后的预览与确认上屏操作原应用的输入框。AI 回复框可选择复制，不会接收键盘文字。
横屏或键盘较高时，面板内部滚动，编辑文本、点击按钮和操作选择菜单不会触发拖动。下拉菜单也受可用空间约束，必要时向上展开，避免遮住键盘。

界面参照用户提供的 Foxy `20f1709` APK 的 Material 表单：圆角细边框、紧凑的行内选择项、描边文本框，
底部操作按钮为描边胶囊；生成前突出“发送 AI”，返回后突出“确认上屏”。颜色跟随键盘主题；文本标签不再占用编辑区声明的高度。
加载时只显示小型进度指示，没有错误时不占用错误提示空间。

API 密钥由 AndroidKeyStore 加密保存，JS 只获取已配置密钥的字段名；请求头由宿主注入。
首次使用会询问应用能力权限，首次连接域名会单独询问是否向该域名发送文本和密钥。
内置助手的 `hosts` 为空，新域名必须来自插件设置中的 HTTPS 地址，脚本不能随意跳转到第三方域名。
设置中的「撤销应用能力授权」同时撤销应用能力和已批准域名；下次使用重新询问。
密码输入框不可使用助手。

## 三种 API 协议

| 设置选项 | 请求路径 | 认证和请求 | 文本解析 |
| --- | --- | --- | --- |
| OpenAI Chat Completions | `/chat/completions` | Bearer、`messages` | `choices[].message.content` |
| Anthropic Messages | `/messages` | `x-api-key`、`anthropic-version`、`system`、`messages`、`max_tokens` | `content[]` 中的 `text` 块 |
| OpenAI Responses | `/responses` | Bearer、`instructions`、`input`、`max_output_tokens`；发送 `store:false` | `output[]` 中 assistant message 的 `output_text` 块 |

每个服务商分别保存协议。旧配置缺少协议字段时仍走 Chat Completions；新增 Anthropic
预设默认走 Messages。API 地址、模型、密钥的字段 ID 保持不变。
原生协议的输出额度默认 4096 Token，可在设置中调整；Responses 的推理 Token 也占额度。
Anthropic API 版本默认 `2023-06-01` 以兼容中转站，可按服务要求修改。
拒绝、输出截断、未完成响应不会作为完整结果上屏；思考和工具调用块不会写入编辑器。
模型名自动更正时，查询 `/models` 使用同一协议的认证方式。

### 自定义地址改在哪里

这个功能需要两层配合：

- **JS 插件**：根据用户配置生成端点和协议请求，`hosts: []`，没有官方/中转站地址或静态域名表。
- **输入法 App**：从设置中的 HTTPS 地址解析主机名，核对请求的主机名后逐域名询问授权，
  从加密存储读取密钥并注入请求头，然后执行 HTTPS 请求。配置值和授权记录由 App 保存。

### `hosts` 声明和 HTTP `Host` 请求头

1. 保存地址本身不会联网或授权。首次发起请求时，App 校验 HTTPS URL，提取规范化主机名。
   对内置助手，主机名必须与当前配置的某个 HTTPS 地址一致；子域名不自动放行。
2. 若该插件尚未获准访问此主机，显示确认弹窗。用户点击「允许」后才保存授权、读取密钥并发送请求。
   已批准的主机再次使用时不重复询问；撤销授权后重新询问。
3. 授权按「插件 ID + 主机名」记录，路径、模型和端口变化不单独触发授权。
   换成尚未授权的新主机时，在首次请求时再次确认。
4. 删除/更改配置后，旧授权记录本身不能使未配置的主机继续获得访问资格。
5. HTTP 的 `Host` 请求头（HTTP/2 中对应 `:authority`）由 OkHttp 根据请求 URL 自动生成，
   非默认端口也随 URL 使用。域名解析和 TLS 主机校验使用 URL 的目标主机；JS 不能手动覆盖 `Host`。
6. 请求不跟随重定向。因此中转站返回跳转时，不会把文本或密钥自动转发给另一个地址。
   模型查询 `/models` 与文本请求使用同一目标主机和协议认证方式。

脚本内置在 APK 的 `assets/bundled/js-apps/llm-assistant.js` 中，也保留 JS 导入机制。
后续在本输入法中换中转站地址只需改设置，无需编辑 JS 或重新打包 APK。
本适配版依赖 App 的动态端点授权，不能直接用于只允许静态 `hosts` 声明的原版 Foxy。

## 插件兼容范围

复用现有 JS 插件导入、元信息、设置和密钥存储，新增 `foxy.app.register`、结构化布局、
`foxy.editor`、`foxy.credentials.list`、`foxy.http.request` 和增量对话框操作。
语音插件仍仅按 `voice.asr` 注册，不会把文本助手加入语音服务列表。

内置脚本合入用户提供的 `llm-assistant-1.23.js` 新增用途和三种语言文案，
适配版本为 `1.23.1-fx`。保留三协议和动态端点，补齐生成、回复、确认与失败的完整状态处理。
内置助手优先于历史同 ID 导入记录，已有设置和密钥继续沿用；外部文件不能再覆盖内置保留 ID。

本次实现针对上述 LLM 插件，支持以上三种协议的非流式文本请求。
尚不覆盖 Foxy 文档中的所有功能：JS App 的 SSE/WebSocket、旧版 flat fields/buttons 布局、
任意 `js.*` 布局命令和多应用选择器未实现。未加入图像输入、多轮对话和工具执行。

协议参考：[Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create)、
[Anthropic API 版本](https://platform.claude.com/docs/en/api/versioning)、
[OpenAI Responses 迁移指南](https://developers.openai.com/api/docs/guides/migrate-to-responses)。

## 验证

```sh
./gradlew -PbuildABI=arm64-v8a :app:testFxDebugUnitTest \
  --tests 'org.fcitx.fcitx5.android.input.voice.js.*' \
  --tests 'org.fcitx.fcitx5.android.input.bar.*' \
  --tests 'org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.ButtonsCustomizerPersistenceTest' \
  :app:assembleFxDebug
```

覆盖真实 JS 的导入、设置/应用声明、空 hosts 下的动态主机校验、HTTP Host 自动生成、任意配置端点、火星文用途、三种协议的路径/认证/请求体、原生文本块解析、截断/拒绝/错误、模型查询、旧设置兼容、预览/确认/取消；
本地编辑器输入分流、失效会话拒绝写入、撤销权限、HTTP 大小限制和重定向策略。
另有 Android 原生图形渲染检查，覆盖浅色/深色布局、编辑区高度、Material 按钮和选择项；
窗口测试覆盖无背景遮罩、不抢焦点、拖动边界、拖动后输入、编辑手势及较高键盘下的布局。
另有实际原生按钮、生产 QuickJS 回调、模拟 HTTP 与真实文本编辑器输入连接的流程测试，覆盖发送、只读回复、确认上屏、失败保留和取消恢复；三协议均检查无结果时的旧 `confirm` 事件和旧回复失效。
测试环境无 Android 设备，也无模型服务的 API 密钥；未进行真机 UI 和真实模型请求验证。
