# 共享导入与解压

::: warning 待补充
本页内容正在撰写修订中。
:::

fx 增强了"从其他应用分享内容到 Fcitx5"的导入流程。

## 共享内容自动导入

- 当其他应用 Share 一个文件到 Fcitx5 时，应用会自动识别内容类型
- 支持的类型自动进入相应导入流程（主题 / 布局 / Popup 预设）
- 无法识别时回退到通用文件导入

## ZIP / 7z 自动解压

- 共享接收到的压缩包可选择直接解压到目录
- 同时支持 **ZIP** 和 **7z** 格式
- 大压缩包：`MAX_ARCHIVE_EXTRACT_BYTES` 已上调到 1 GiB（避免上游较小的默认上限）

## 入口

- 在其他应用中点击 Share → 选择 Fcitx5
- 或在文件管理器中点击文件 → 选择 "用 Fcitx5 打开"

## 相关页面

- [QR 分享与导入](/features/theme/share-import) —— 另一种导入方式
- [主题编辑器](/features/theme/theme-editor)
