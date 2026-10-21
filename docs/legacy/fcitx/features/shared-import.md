# 共享导入与解压

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/features/shared-import.md)
:::


本版增强了"从其他应用分享内容到 Fcitx5"的导入流程。

## 共享内容自动导入

- 当其他应用 Share 一个文件到本应用时，应用会自动识别内容类型
- 支持的类型自动进入相应导入流程（主题 / 布局 / Popup 预设 / 图标主题）
- 无法识别时回退到通用文件导入
- 分享接收与二维码长图处理**移出主线程**，不再阻塞输入

## ZIP / 7z 自动解压

- 共享接收到的压缩包可选择直接解压到目录
- 同时支持 **ZIP** 和 **7z** 格式（7z 导入带 SDK 守卫，避开部分 ROM desugar 缺失的 nio API）
- 大压缩包：解压上限 1 GiB（`MAX_ARCHIVE_EXTRACT_BYTES`），且解压有总量与路径校验，配合主题包的条目限额防御恶意包

## 入口

- 在其他应用中点击 Share → 选择本应用（靓企鹅·中州韵 / Fcitx5.fx.rime）
- 或在文件管理器中点击文件 → 选择"用 Fcitx5.fx.rime 打开"

## 相关页面

- [QR 分享与导入](/legacy/fcitx/features/theme/share-import) —— 另一种导入方式
- [主题编辑器](/legacy/fcitx/features/theme/theme-editor)
