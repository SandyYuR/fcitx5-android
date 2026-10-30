# 中州鹅功能总览

本页介绍当前 **Rime 直连版**。Rime 方案决定输入规则和词典，屏幕键盘布局决定按键与手势；导入一种不会自动得到另一种。
首次使用从[安装](/guide/installation)和[快速上手](/guide/quick-start)开始，完整操作见[用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)。

## 输入方案与候选

| 功能 | 能做什么 | 详细说明 |
| --- | --- | --- |
| 内置 Rime 引擎 | 直接调用 librime，无需安装 Rime 引擎插件 APK；中文方案、完整词典与模型需自行导入 | [方案与部署](/features/rime-enhancements) |
| 方案 ZIP 与数据目录 | 将完整方案导入应用目录或可访问的外部目录，管理多个独立工作目录 | [用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN#导入方案与万象) |
| 语言、方案与开关 | 语言键短按交给方案切换中英文，长按打开方案菜单；状态区管理分组开关 | [语言键](/features/keys/language-key) |
| 候选栏与窗口 | 调整字体、注释、序号、间距和圆角；长候选可横向浏览，展开候选与浮动窗口分别配置 | [候选指南](/features/candidate-window) |
| 选词与退格手势 | 按住候选后滑动选字或打开菜单；合成中长按退格上滑只清编码，正文单独处理 | [Rime 功能](/features/rime-enhancements) · [退格键](/features/keys/backspace-key) |

部署、同步或配置重载期间，等待引擎恢复后再输入；这些维护阶段不处理按键。初始化等待与正常输入的事件顺序是另一回事，不能把“部署时继续输入”当作支持的操作。

## 键盘形态、布局与宏

| 功能 | 能做什么 | 详细说明 |
| --- | --- | --- |
| 浮动、分体、单手 | 调整大小和位置；横屏可自动切换形态，浮动移动手柄可自动隐藏 | [浮动](/features/keyboard/float-keyboard) · [分体](/features/keyboard/split-keyboard) · [单手](/features/keyboard/one-handed) |
| 布局文件与方案子布局 | 保存多份完整布局，为不同 Rime 方案设置专用排列和独立分体布局 | [布局编辑器](/features/editor/layout-editor) |
| 可视化编辑 | 编辑行结构、键位、弹出字符、宏和字体集，支持草稿、保存确认与 JSON | [Popup](/features/editor/popup-editor) · [宏编辑器](/features/editor/macrokey-editor) · [字体](/features/editor/fontset-editor) |
| 按键类型与字段 | 字母、宏、切层、空格、退格等按键；空白占位键用于排版，显式显示文本保留原样 | [按键参考](/features/keys/overview) |
| 长按、上下滑动 | 分别配置动作；空格光标移动与自定义划动动作互斥 | [手势](/features/keyboard/swipe-actions) · [空格键](/features/keys/space-key) |
| 输入时覆盖按键 | 组合状态下临时替换键面和动作；这是布局 Compose Override，不是完整 XKB Compose | [Compose Override](/features/keyboard/compose-override) |
| 电脑编辑与分享 | 局域网配对编辑，或离线网页编辑后二维码导入；分享单份配置 | [在线编辑](/features/online-editor) · [二维码](/features/theme/share-import) |

## 主题、工具栏与符号

| 功能 | 能做什么 | 详细说明 |
| --- | --- | --- |
| 键盘主题 | 自定义颜色、图片、圆角、图标、磨砂与字体；随机主题可复制为自定义主题 | [主题编辑器](/features/theme/theme-editor) · [Monet](/features/theme/monet) |
| 设置界面外观 | 明暗、纯黑、Monet、手动配色，Android 11+ 可跟随键盘取色；修改即时生效 | [设置外观](/features/theme/settings-appearance) |
| 页面动画与返回 | 独立前进/返回样式、350/500/650 ms；Android 14+ 提供预测性返回，默认关闭，保留已有选择 | [动画设置](/features/theme/settings-appearance#动画与返回手势) |
| Kawaii Bar | 自定义按钮、宏与图标，调整工具栏大小；语音按钮参与排列，收起键盘按钮独立保留 | [工具栏](/features/kawaii-bar) |
| 符号、表情、颜文字 | 左侧分组栏或 Gboard 式底部栏，最近使用记录，三类数据支持自定义 JSON | [符号面板](/features/symbol-panel) |

## 语音输入

| 方式 | 能做什么 | 详细说明 |
| --- | --- | --- |
| 内置 JS 插件 | 通义千问、豆包、腾讯云语音识别和实时语音翻译；在应用内填参数与密钥 | [JS 语音](/features/voice-input-js) |
| 导入自定义 JS | 导入自包含脚本，逐插件批准网络主机；密钥由宿主保存，不交给脚本 | [导入与授权](/features/voice-input-js#导入、覆盖与移除) |
| AIDL 外部提供方 | 接入实现协议的独立 Android 应用，适合本地模型或厂商 SDK | [外部提供方](/features/voice-input) |
| 快速切换 | 工具栏语音键短按识别、长按选择首选引擎；JS 引擎可直接进入插件设置 | [语音入口](/features/voice-input) |

## 数据与常用工具

| 功能 | 能做什么 | 详细说明 |
| --- | --- | --- |
| 剪贴板历史与搜索 | 保存、固定、分词选择与本机搜索；可连接 SyncClipboard、Oneclip、ClipCascade | [剪贴板](/features/clipboard-sync) |
| 完整备份与恢复 | 默认密码加密 `.fcbak`，可选兼容 ZIP；备份布局、偏好、Rime 数据与导入插件 | [备份](/features/backup-restore) |
| 更新与下载 | 本仓库 Release/Nightly、镜像与 hosts，受支持路径可后台下载和断点恢复 | [更新](/features/update-checker) |
| 分享导入 | 从系统分享识别支持的布局、主题等文件，处理压缩包 | [共享导入](/features/shared-import) |
| 文本编辑 | 光标、选择、复制粘贴和可配置文本编辑栏 | [文本编辑](/features/text-editor) |
| 设置搜索与按键反馈 | 中文名、别名和拼音查找设置；自定义音效、振动与反馈 | [用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN#设置搜索与按键反馈) |

## 文档与版本对应

当前功能由默认分支源码核对，逐提交记录见[代码与文档对照](/maintainer/changes/)。继承的前端功能继续由当前功能页说明；旧 Fcitx 引擎实现只在[历史档案](/legacy/)中保留。
云端语音识别效果、设备后台策略以及不同编辑器的输入行为仍受服务和设备环境影响。
