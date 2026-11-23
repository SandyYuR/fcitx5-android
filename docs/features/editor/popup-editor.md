# Popup 预设编辑器

**PopupPreset 编辑器** 配合 `config/PopupPreset.json` 配置文件，让你能可视化地编辑按键**长按弹出的候选字符**（比如按住 `a` 弹出的一排可选字符）。

## 能力

- 单份配置：所有按键的弹出字符都保存在同一个 `config/PopupPreset.json`，不提供多套预设切换
- 按键与候选字符可增删改（点按编辑、长按删除），保存为紧凑 JSON
- 右上角菜单可通过二维码图片分享、扫码导入或从二维码图片导入

## 入口

- **应用主页 → 键盘 → 键盘布局自定义 → 弹出字符设定**（2026-09-29 起归入本页——它改的是长按按键弹出的字符，属键盘的内容定义）
- 也可以在电脑浏览器中使用 [在线编辑器](https://sandyyur.github.io/f5a-see-me/){target="_blank" rel="noopener"} 修改 Popup 预设，再通过二维码导入到手机端。

## 配置文件

- 存放于应用私有目录的 `config/PopupPreset.json`；
- **没有用户文件时**使用应用内置的一套默认弹出定义；只有你在编辑器里保存过自己的版本后，才会整体替换默认定义；
- 长按弹窗的判定做了缓存前置（不再每次同步读盘），双指同时打字时不会误清已打开的弹窗。

## 相关页面

- [键盘布局编辑器](/features/editor/layout-editor)
- [在线编辑器](/features/online-editor)
- [QR 分享与导入](/features/qr-share-import)
