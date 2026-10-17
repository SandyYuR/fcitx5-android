# 本会话构建版交付索引

当前产品为 **中州鹅**，`io.github.sandyyur.rime.direct`，工作分支 `feat/direct-rime-prototype`，
[草稿 PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4)。
操作入口见[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)，
开发入口见[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)。

## 本会话版本演进

| 阶段 | 作用与记录 |
| --- | --- |
| 最小直连实验 | 验证 JNI/librime、输入会话和 InputConnection；仅诊断界面，[原型记录](DIRECT_RIME_IMPLEMENTATION_2026-10-16.md) |
| 完整主应用 | 接回原键盘、候选、设置、主题与布局编辑器，[前端记录](DIRECT_RIME_FRONTEND_2026-10-16.md) |
| 方案与标签 | 注释由 Rime 决定、状态区互斥开关分组、方案菜单去重；空格键、英文模式与中州鹅名称 |
| 性能与迁移 | 纠正基线为含 Fcitx 的默认分支历史提交，按实际万象配置回放，[审计与原始数据](DIRECT_RIME_AUDIT_2026-10-17.md) |
| 旧后端清理 | `a49eb9b8` 删除旧原生后端、构建工程和子模块，[验证记录](DIRECT_RIME_CLEANUP_2026-10-17.md) |
| 无调用方清理 | `6c470b8b` 删除 FcitxUtils 等工具及旧 JNI 转换，[清单](DIRECT_RIME_UNUSED_CODE_AUDIT_2026-10-17.md) |
| 当前设置整理 | 模式/方案编码区提示、单一 Rime 设置入口、重写关于页、旧快捷方式兼容转向 |

## 已确认的运行结构

主应用通过单一 owner 直接调用 librime；不运行 Fcitx Core、fcitx5-rime 或 AndroidFrontend 原生层。
APK 仅打包直连桥接库和 C++ 运行库。原 Android 前端、键盘、连接和配置模型继续承担界面功能。
“没有 Fcitx 原生中间层”和“仓库中完全没有任何 Fcitx 命名/来源”不是同一结论。
当前保留项及原因在[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)列明。

当前万象历史对照结果：候选事件 P50 约降低 6%、P95 约降低 7%，
其范围限于固定 Debug 模拟器回放，不是本轮设置调整的新性能结论。
个人长期词库、长句、快速连击、完整 compose 和按应用行为等价仍需继续验证。

## 验证证据

- 无调用方清理：471 项 JVM 单测、Debug/Release、18 组删除类的 DEX 核验，见
  [本地记录](direct-rime-unused-code-validation-2026-10-17.json)。
- 对应源码/记录提交 `4456c110` 的两个 CI 工作流全通过，含 6 项完整前端设备测试，见
  [CI 归档](direct-rime-unused-code-ci-2026-10-17.json)。
- 当前设置与提示版本的源码、APK 哈希、签名、构建及设备回归结果见
  [当前设置版验证](direct-rime-settings-validation-2026-10-17.json)。

当前主应用安装包为 `zhongzhougoose-settings-arm64-v8a-debug.apk`。
APK 二进制保留在本会话 artifacts，仓库只保存版本来源与校验摘要。
以后获取构建请在 Actions 选择本分支的 `direct-rime-frontend-arm64-debug`；
其他 ABI 可使用 PR CI 产物或按维护手册编译。

工作区文件与四个临时性能分支的整理见[整理记录](DIRECT_RIME_WORKSPACE_CLEANUP_2026-10-17.md)。
