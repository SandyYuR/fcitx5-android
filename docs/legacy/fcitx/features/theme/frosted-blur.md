# 磨砂按键

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/features/theme/frosted-blur.md)
:::


**Frosted Key Blur** —— 按键磨砂模糊效果，让按键呈现毛玻璃质感，配合自定义背景图效果尤佳。

## 能力

- 高斯模糊半径可调
- 预览与实际渲染保持一致（预览同步修复）
- 与背景图、Monet 主题协同
- 模糊区域重建收敛为**有界重试**、每键一次裁剪一次绘制（本版性能加固，避免异常输入下反复重建）

## 注意事项

- 模糊渲染会消耗一定 GPU 资源，低端设备可能感受到延迟
- 半透明键面下文字对比度需配合主题色调整

## 相关页面

- [主题编辑器](/legacy/fcitx/features/theme/theme-editor)
- [Monet 编辑器](/legacy/fcitx/features/theme/monet)
