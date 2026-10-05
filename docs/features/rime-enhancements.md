# Rime 引擎（内置）

靓企鹅·中州韵把 Rime 作为**唯一输入引擎**直接编入主 APK：静态链接 `librime.a`，Rime 共享数据（`default.yaml`、`key_bindings.yaml`、`punctuation.yaml`、`symbols.yaml` 等公共预设资源）随包分发，OpenCC 指向包内数据。**不需要安装任何插件 APK**。

::: warning 不预置输入方案
随包提供的只有 Rime 的公共预设资源。**输入方案（`*.schema.yaml` + 词典）需要自备**：放入用户数据目录、patch `schema_list`、执行部署。完整步骤见 [快速上手](/guide/quick-start)。
:::

## 用户数据目录

- 位置：应用外部文件目录下的 **`data/rime`**；
- 入口：**应用主页 → 输入与候选 → 中州韵设置 → 用户数据目录**（长按该入口可直接打开当前 profile 对应的物理目录）；
- 方案文件、`default.custom.yaml`、`essay.txt`（可选）、用户词典都在这里；
- 升级应用不会删除该目录内容；卸载应用会。

## 方案与部署

- **部署**：读取 YAML 与词典源文件生成本机运行数据。放好方案后必须执行一次；之后修改任何配置文件也需要重新部署才生效。
- **只有配置真的变了才部署**：本版用配置文件**内容指纹**判断是否需要部署（上游用目录时间戳，会被词典落盘、临时目录清理等无关活动推新，导致每次冷启动都全量部署）。真实改了配置、新增了词典或首次安装仍会正常触发部署。
- **部署期间打字**：键盘预编辑/候选区显示「**正在部署**」，此间按下的字母**只被丢弃**——不进编码区，也不会被当作普通字符直接上屏（这是旧版的一个真实 bug，已修复）。
- **部署失败**：通常是 `schema_list` 列了不存在的方案、或 YAML 有语法/缩进错误；先修复文件再重试。

## 同步

- **用户数据同步**：把 Rime 用户数据与同步目录交换，用于用户词典的迁移与备份；在 **中州韵设置** 中主动执行；
- 为避免退出输入法时长时间卡顿，本版**不会**在每次退出时强制执行完整 Rime 同步——重要数据请主动同步，并定期 **导出用户数据** 整包备份。

## 输入行为定制（本版差异）

- **语言键**：短按向 Rime 发送一次独立 Shift，交给方案的 `ascii_composer`/`switch_key` 处理中英文切换；**长按弹出 Rime 方案选单**，点选即切换方案。想切换到其他 Android 输入法，请长按**工具栏**的语言按钮——两者不是同一个入口。
- **候选 tabs**：候选栏的音节 tab 由定制 API（`RimeGetInputTabs` / `RimeSelectTab`）提供，非法 UTF-8 标签会在适配层过滤，JNI 侧再兜底净化，避免 CheckJNI 直接中止进程。
- **候选词手势**：按住候选**上滑**弹出选字窗（滑到哪个字抬手提交该字）；按住**下滑**呼出操作菜单（如「忘记词汇」）；未按住时的滑动仍归候选列表滚动。
- **忘记词汇**：候选下滑菜单里的「忘记词汇」会连删同音词的用户记录（配套修复，不会误伤）。
- **长按退格保护**：长按退格把编码删空后继续按住，不会删到已上屏正文，直到抬指（与万象桌面行为一致）；「长按连删后上滑」则可一次性清空整个输入框。

## 引擎链路来源

本版的 Rime 不使用官方预编译产物，而是一条自有构建链：

| 环节 | 仓库 | 说明 |
|------|------|------|
| 适配层 | [SandyYuR/fcitx5-rime](https://github.com/SandyYuR/fcitx5-rime) | 官方 5.1.17 基线 + fxliang 定制 + 部署期按键吞掉、方案选单、tabs 过滤等修复 |
| librime 静态库 | [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder) | 固定官方 librime 提交，按固定顺序应用定制补丁，构建多 ABI 静态库 |
| 静态库产物 | [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt) | prebuilder CI 推送产物，主 APK 链接的就是它 |

librime 定制补丁包括（按应用顺序）：fxliang 功能补丁（tabs 可选等）、音节缓存、词典并行部署、用户词典缓存、词典文件重映射修复、忘记词汇连删同音词修复、万象（amzxyz）的 `rewrite` 滤镜（PR #1232），以及**必须排在末位**的配置指纹补丁。

::: warning 不要用官方 prebuilt 覆盖
直接用官方预编译产物替换会静默丢失 tabs、音节缓存、用户词典缓存等定制能力。
:::

## 注意事项

- 部分国产 ROM 的后台限制会影响键盘进程常驻与剪贴板同步，见 [OEM 自启动与后台限制](/troubleshooting/oem-startup)；
- 删除应用前请备份 `data/rime` 目录（或使用 **导出用户数据**）；
- 「删除并同步运行数据」不是普通刷新按钮：它会重建运行数据，应在已备份后用于修复损坏或版本残留。

## 相关页面

- [快速上手](/guide/quick-start)
- [核心概念](/guide/concepts)
- [从上游迁移](/guide/migrate-from-upstream)
- [常见问题](/troubleshooting/faq)
- [用户指南（rime-docs 分支）](https://github.com/SandyYuR/fcitx5-android/blob/rime-docs/docs/RIME_ONLY_USER_GUIDE_zh-CN.md)
