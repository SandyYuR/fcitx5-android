# 维护文档

先选项目版本，再按任务找资料。**中州鹅**使用 Kotlin 前端、单一 owner 线程和 JNI 直接调用 librime；**靓企鹅中州韵**是保留 Fcitx 中间层的历史版本。

<div class="entry-grid">
  <a class="entry-card" href="./DIRECT_RIME_MAINTAINER_GUIDE_zh-CN"><span class="eyebrow">中州鹅 · 现行维护</span><strong>构建、引擎与验证</strong><p>代码结构、构建参数、librime 更新、CI 门禁和排查入口。</p><span class="entry-link">阅读维护手册 →</span></a>
  <a class="entry-card" href="./catalog"><span class="eyebrow">完整资料库</span><strong>按版本与主题查找</strong><p>涵盖现行协议、阶段报告、性能实验和旧版批注；保留每份资料的来源。</p><span class="entry-link">打开全部维护资料 →</span></a>
</div>

## 我现在要做什么

| 任务 | 阅读入口 |
| --- | --- |
| 在 Windows 构建和排查环境 | [本机环境](/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN) → [维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) |
| 在 Windows 修改引擎并生成本地 Debug | [源码 → 开发 SDK → APK](/maintainer/local-engine-development) |
| 更新 librime、插件、依赖与 Android SDK | [完整更新与回退流程](/maintainer/engine-update) → [SDK 配方说明](https://github.com/SandyYuR/librime/blob/zhongzhou/downstream/android/README.zh-CN.md) |
| 核对默认分支功能与文档 | [远端重放与功能复核](/maintainer/changes/2026-11-06-remote-review) → [前轮本地复核](/maintainer/changes/2026-11-05-local-review) → [现行功能源码表](/maintainer/changes/feature-coverage) |
| 开发语音插件或外部提供方 | [JS 宿主协议](/maintainer/implementation/js-voice-plugins) · [AIDL 接入入口](/features/voice-input) |
| 维护同步、下载、备份与恢复 | [现行实现协议](/maintainer/implementation/) |
| 查输入链路、候选和性能的由来 | [直连版历史阅读路线](/maintainer/history/) |
| 找截图、日志、JSON、测试结果和 ZIP | [验证证据目录](/maintainer/evidence)；附件保留在对应报告目录 |
| 追溯保留 Fcitx 层的旧版本 | [靓企鹅中州韵维护报告](/legacy/fcitx/maintainer/) · [后续批注与方案](/legacy/fcitx/notes/) |
| 为文档站增补或迁移页面 | [分类与维护约定](/maintainer/documentation) · [参与文档修改](/about/contribute-docs) |

## 阅读记录时注意适用范围

现行手册和实现协议描述默认分支目前的行为。带日期的报告描述**当时的代码与验证条件**，不会因主分支继续更新而自动变成当前结论。

历史 Fcitx 报告中仍有参考价值的前端问题，已在[139 项对照记录](/maintainer/history/DIRECT_RIME_LEGACY_REVIEW_COMPARISON_2026-10-24)中重新分类；该对照本身也有日期。
要判断某个问题今天是否仍存在，需要再核对源码、调用点与实际测试。模拟器、JVM 和历史 APK 的结果不能替代当前版本真机验收。

普通使用问题请查看[用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)与[功能指南](/features/overview)。
