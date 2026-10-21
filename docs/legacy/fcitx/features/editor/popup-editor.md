# Popup 预设编辑器

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/features/editor/popup-editor.md)
:::


**PopupPreset 编辑器** 配合 `config/PopupPreset.json` 配置文件，让你能可视化地编辑按键**长按弹出的候选字符**（比如按住 `a` 弹出的一排可选字符）。

## 能力

- 多预设管理（可创建多套 popup 方案切换使用）
- 紧凑 JSON 格式（fx 后期统一为更紧凑的存储形式以减小文件体积）
- 可二维码分享 / 扫码导入

## 入口

- **应用主页 → 键盘 → 键盘布局自定义 → 弹出字符设定**（2026-09-29 起归入本页——它改的是长按按键弹出的字符，属键盘的内容定义）
- 也可以在电脑浏览器中使用 [在线编辑器](https://sandyyur.github.io/f5a-see-me/){target="_blank" rel="noopener"} 修改 Popup 预设，再通过二维码导入到手机端。

## 配置文件

- 存放于应用私有目录的 `config/PopupPreset.json`；
- **没有用户文件时**使用应用内置的一套默认弹出定义；只有你在编辑器里保存过自己的版本后，才会整体替换默认定义；
- 长按弹窗的判定做了缓存前置（不再每次同步读盘），双指同时打字时不会误清已打开的弹窗。

## 相关页面

- [键盘布局编辑器](/legacy/fcitx/features/editor/layout-editor)
- [在线编辑器](/legacy/fcitx/features/online-editor)
- [QR 分享与导入](/legacy/fcitx/features/theme/share-import)
