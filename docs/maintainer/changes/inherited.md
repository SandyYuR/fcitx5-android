# 继承功能与旧提交索引

相对已核实存在的 fx 基线 `3ad25fc9b`，直连接入前共有 **261 个提交**。下面逐条登记提交、改动主题与文档位置。

这些提交来自仍含 Fcitx 层的阶段，包括后来继续沿用的 Android 前端功能，也包含已经移除的原生实现和已被回退的方案。此处是历史与文档的分类对照，不表示每个旧补丁都仍在当前代码生效。当前功能以[功能总览](/features/overview)与[用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)为准。

每个提交的完整改动路径收录在[提交映射 JSON](./commit-map-2026-10-30.json)。现行直连阶段的单独核对见[55 项对照](./direct-rime)。

## 构建、整理与文档

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [63d41b693](https://github.com/SandyYuR/fcitx5-android/commit/63d41b6930afe008bd1776419602b885a098737e)<br>2026-09-04 | ci: 为单元测试启用 testDebugUnitTest 并收紧 nightly 发布分支 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [0fdc7030f](https://github.com/SandyYuR/fcitx5-android/commit/0fdc7030f9968620392c89ffca5b60e832203466)<br>2026-09-04 | fix(日志): Logcat 可重复打开并回收子进程 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [9c524ad2d](https://github.com/SandyYuR/fcitx5-android/commit/9c524ad2d10db0709b47a7d74f36c7307ae593c8)<br>2026-09-04 | perf(分享导入): 分享接收与二维码长图保存移出主线程 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [518baea49](https://github.com/SandyYuR/fcitx5-android/commit/518baea4987d016d9170999af5b80b567b77c14d)<br>2026-09-04 | perf(模糊效果): 模糊遮罩每键一次裁剪一次绘制 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [c0e715053](https://github.com/SandyYuR/fcitx5-android/commit/c0e715053a5c8f0af53da7780e4da87f4a8b010a)<br>2026-09-04 | perf(水波纹): 缓存 RadialGradient 不再每帧重建 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [db875357c](https://github.com/SandyYuR/fcitx5-android/commit/db875357c48f4aae1c1f205b6f614b91ba71e3a1)<br>2026-09-04 | perf(模糊效果): 模糊区域重建收敛为有界重试 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [71dc6a8b8](https://github.com/SandyYuR/fcitx5-android/commit/71dc6a8b8b2d717956f3c2fa0c15ecc24caea61b)<br>2026-09-04 | fix(快捷磁贴): 真正启动/停止同步服务 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [a8d1413c1](https://github.com/SandyYuR/fcitx5-android/commit/a8d1413c1efc8f9bf9064211e52c8d71f158bcce)<br>2026-09-04 | fix(签名校验): 包签名比对兼容 API 23~27 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [3e3878e00](https://github.com/SandyYuR/fcitx5-android/commit/3e3878e0036457a95b56c31c0b3c939c37b614e5)<br>2026-09-04 | fix(分享导入): 7z 导入加 SDK 守卫，避开 desugar 缺失的 nio | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [cec6b842e](https://github.com/SandyYuR/fcitx5-android/commit/cec6b842e9828680965a4e14bacef2f31b557786)<br>2026-09-04 | ci: 插件构建使用本 fork 的 applicationId | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [671982562](https://github.com/SandyYuR/fcitx5-android/commit/6719825624392f2b951c8d64408cea0b2643b84d)<br>2026-09-05 | ci: 移除 nightly release 与 mainline 构建任务 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [b1bba67b3](https://github.com/SandyYuR/fcitx5-android/commit/b1bba67b388a76a9489bd4aea27aba42074ca944)<br>2026-09-05 | refactor(插件): 删除 9 个其他语言/功能插件模块 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [a5a75db48](https://github.com/SandyYuR/fcitx5-android/commit/a5a75db48b30f0dffe067e23cc6277dfb859e80e)<br>2026-09-05 | refactor(构建): 移除 mainline flavor 及任务别名/APK 兼容拷贝 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [2215cc235](https://github.com/SandyYuR/fcitx5-android/commit/2215cc235a1dba6c8acc52a58f049cc4e7379086)<br>2026-09-05 | ci: 删除个人 fork 无用的 workflow（fdroid/pull_request/nix/publish） | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [16d5444d8](https://github.com/SandyYuR/fcitx5-android/commit/16d5444d8e5e3b605e96e6057888c6f728bd87d9)<br>2026-09-05 | refactor(native): 删除内置拼音 native 链路（libime/pinyin/table/customphrase），保留 opencc | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [c86c32c34](https://github.com/SandyYuR/fcitx5-android/commit/c86c32c34ede046d299345498ac7e566084dece3)<br>2026-09-05 | refactor(构建): 删除拼音相关 gradle 依赖与 lib 模块，CI 移除 plugins 构建 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [cc2f2d6bc](https://github.com/SandyYuR/fcitx5-android/commit/cc2f2d6bcafc15feb468094b039ae87b90264b7c)<br>2026-09-05 | refactor(构建): 删除 lib/plugin-base 模块与 transifex 残留配置 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [d85068c04](https://github.com/SandyYuR/fcitx5-android/commit/d85068c04c5a27cc0b274e5913b1b6f0be08b5c5)<br>2026-09-05 | refactor(插件框架): 删除插件检测框架与插件运行时 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [1f6085df4](https://github.com/SandyYuR/fcitx5-android/commit/1f6085df4a682a8a8a0b93e52c1d378dfb45aadd)<br>2026-09-05 | fix(远程服务): 删除 FcitxRemoteService 残留的 callingPackage 引用 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [46430b40e](https://github.com/SandyYuR/fcitx5-android/commit/46430b40e110af4659bcaca5a716c4c5bfb4a9d1)<br>2026-09-05 | ci: 构建失败时将编译错误输出为 annotations | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [d1386cfd5](https://github.com/SandyYuR/fcitx5-android/commit/d1386cfd57f9ba6720cf57495fd2eb988f5fe76f)<br>2026-09-05 | fix(应用): 移除 FcitxApplication 悬空的 @Volatile 注解 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [7d4c3cf67](https://github.com/SandyYuR/fcitx5-android/commit/7d4c3cf67e5cdb50e9d07e81f4afb610342f51f4)<br>2026-09-05 | feat(构建): 应用包名改为 org.fcitx.fcitx5.android.fx.rime 以便与 fx2 共存安装 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [480cdd26b](https://github.com/SandyYuR/fcitx5-android/commit/480cdd26b39ce2c2c06c0021b365671ed302d05e)<br>2026-09-05 | refactor(组件): 移除 androidkeyboard/imselector/spell/unicode 组件 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [803389447](https://github.com/SandyYuR/fcitx5-android/commit/80338944719c1bfb4bc8b1cb43675f220aba4bd0)<br>2026-09-07 | feat(品牌): 中文名改为靓企鹅.中州韵（仅简繁中文，西文保持 Fcitx5.fx.rime） | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [d4eedecba](https://github.com/SandyYuR/fcitx5-android/commit/d4eedecbabfac664e4a15958cb1ad5ca460d9ea4)<br>2026-09-07 | feat(品牌): 应用名定为靓企鹅·中州韵并补全 README 功能说明 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [0092a3f0a](https://github.com/SandyYuR/fcitx5-android/commit/0092a3f0a6c986b62028e3f7ce16b05d1a501f4a)<br>2026-09-10 | docs(README): 文档引用指向 rime-docs 分支（文档分拆收尾） | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [a2db421a2](https://github.com/SandyYuR/fcitx5-android/commit/a2db421a287dd9c178af8f1625b35fca80943a53)<br>2026-09-14 | ci(单测): 新增独立单测 job，不阻塞 Nightly 出包 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [834eb632d](https://github.com/SandyYuR/fcitx5-android/commit/834eb632d24ecae0e4f63b6088eef9f9fb082028)<br>2026-09-15 | refactor(内置资源): assets/bundled 中文目录改为英文目录 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [67d7ed5ef](https://github.com/SandyYuR/fcitx5-android/commit/67d7ed5efb9c0130435e3e5fe8fd9960e03bf528)<br>2026-09-15 | ci(android-sdk): setup-android 显式只装 platform-tools，修复环境步骤失败 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [cb8e94fad](https://github.com/SandyYuR/fcitx5-android/commit/cb8e94fad5e1a7c93aa16ea5f813e7133600a520)<br>2026-09-17 | perf(日志): release 热路径不再构造被丢弃的 DEBUG 字符串 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [5147a9d24](https://github.com/SandyYuR/fcitx5-android/commit/5147a9d245bd411832edb9a25bb271a6f32f4da3)<br>2026-09-17 | fix(Settings): 修复创建中州韵目录后视图销毁导致的崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [dd3a47c4c](https://github.com/SandyYuR/fcitx5-android/commit/dd3a47c4c8f7d08743afd9f769ee79e596d2a5d1)<br>2026-09-24 | fix(语言): zh_*_#Hans/Hant 回退到 zh_CN/zh_TW | 回退记录，不能将被回退的方案按标题列为现有功能；查当前主题说明。 [阅读文档](/maintainer/history/) |
| [4f93612f4](https://github.com/SandyYuR/fcitx5-android/commit/4f93612f4d0426ec0cfc20d31d8716c69ffc1736)<br>2026-09-28 | feat(符号面板): 换成 Foxy 风格并支持直达与自定义数据 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [7a0c0730f](https://github.com/SandyYuR/fcitx5-android/commit/7a0c0730ff3a10da886e5c0e05563ab211109614)<br>2026-09-28 | 文档(README): 重写为用户视角，突出不预置方案与快速上手 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [d32916288](https://github.com/SandyYuR/fcitx5-android/commit/d32916288838b4e40df1f040c1675261327e1519)<br>2026-09-29 | chore(文档/CI): README 补全「相对 fxliang 的主要改动」，Nightly 正文去掉固定头 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [0077c4010](https://github.com/SandyYuR/fcitx5-android/commit/0077c4010e216272cab844b80e75967a366357bf)<br>2026-10-08 | ci(工作流): 为拉取请求增加单测与调试构建 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [5085689f7](https://github.com/SandyYuR/fcitx5-android/commit/5085689f73facb2551cf91de7e53c1192b8f5696)<br>2026-10-11 | docs(README): 对齐 10-07~10-10 一批用户可见改动 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [3d0af21ec](https://github.com/SandyYuR/fcitx5-android/commit/3d0af21ec431b49bf5d9f4fb86a7d3b0cc22a51b)<br>2026-10-11 | fix(分享导入): Loading 只覆盖 detect 阶段，不再盖住后续交互对话框 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |
| [c5d4f80ab](https://github.com/SandyYuR/fcitx5-android/commit/c5d4f80abf10be875b59a332c2145ecbb6f3b2ac)<br>2026-10-21 | 修复：扩大符号面板最近使用上限 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/maintainer/history/) |

## 键盘形态

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [73fa05aff](https://github.com/SandyYuR/fcitx5-android/commit/73fa05afffe3171dc627bff44d98d5cb95f299c5)<br>2026-09-04 | fix(更新器): 注册镜像列表 Activity 并清理分屏键盘死代码 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [0160e49a2](https://github.com/SandyYuR/fcitx5-android/commit/0160e49a2687e8955e71a4e2939376a6f560a03c)<br>2026-09-04 | fix(键盘): 清理 SplitKeyboardStateManager 死代码并 recycle 触摸事件 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [e19a361c6](https://github.com/SandyYuR/fcitx5-android/commit/e19a361c6662b1a82ef9dca7e66b3703affca62e)<br>2026-09-04 | fix(键盘手势): resetState 与取消手势不再被运行时开关守卫 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [9ab92bd90](https://github.com/SandyYuR/fcitx5-android/commit/9ab92bd90eb9a5cd89458bc0ac77f51c912fadb1)<br>2026-09-04 | fix(键盘): 静态 keydef 缓存设上限 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [cfc057edb](https://github.com/SandyYuR/fcitx5-android/commit/cfc057edb5191d215b928a71083358765387ab8b)<br>2026-09-04 | fix(键盘手势): baseline 记录在包装前，监听链不再增长 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [ba5f52bd1](https://github.com/SandyYuR/fcitx5-android/commit/ba5f52bd16896fe0c718694d267aadc152d41d38)<br>2026-09-04 | perf(键盘): textKeys 缓存化，不再全树遍历 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [34cc7473d](https://github.com/SandyYuR/fcitx5-android/commit/34cc7473d8581f0b3f822df0b65c9bcba994d25f)<br>2026-09-05 | perf(键盘): 修饰键保持时长 150ms 降到 50ms，并更正其依据 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [718e0a6e6](https://github.com/SandyYuR/fcitx5-android/commit/718e0a6e61c9a8f55326813dcb8746654fb16577)<br>2026-09-07 | test(键盘): 修正 BACK 用例注释与 KeyboardWindow 实际门控一致 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [1a883904c](https://github.com/SandyYuR/fcitx5-android/commit/1a883904c9e035a60cc931dfe73dd8205d6c051a)<br>2026-09-09 | fix(悬浮键盘): 横屏切换后延迟刷新并按配置失效尺寸缓存 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [7a480550a](https://github.com/SandyYuR/fcitx5-android/commit/7a480550a4a81024ddc7297056732b22a6383c0c)<br>2026-09-17 | fix(辅助选择栏): 纵向辅助栏跟随悬浮高度均分排布 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [ed1ef4290](https://github.com/SandyYuR/fcitx5-android/commit/ed1ef4290d07021dc8fb4de71531397615e59b0b)<br>2026-09-17 | fix(悬浮键盘): 移动把手顶部空间不足时翻到底部 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [4cc4c8e49](https://github.com/SandyYuR/fcitx5-android/commit/4cc4c8e49ec61f7b1469500b6ec09731c126d63c)<br>2026-09-20 | feat(虚拟键盘): 键盘高度百分比默认按真实屏幕尺寸计算 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [9ec6cc66d](https://github.com/SandyYuR/fcitx5-android/commit/9ec6cc66d7385632211019fd9c49a496dd425013)<br>2026-10-02 | feat(设置): 分体/浮动/单手归并一页、键盘尺寸改名、文案统一 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [34fd116f2](https://github.com/SandyYuR/fcitx5-android/commit/34fd116f2fd82076f52c5305a1d6237692e1c480)<br>2026-10-02 | feat(浮动键盘): 新增「横屏时自动使用浮动键盘」 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [c56b2d993](https://github.com/SandyYuR/fcitx5-android/commit/c56b2d99369651197d4ba71c1bf847411363a8c1)<br>2026-10-02 | feat(分体键盘): 奇数字母行分体时复制中间字母键（含开关） | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [d62e41e49](https://github.com/SandyYuR/fcitx5-android/commit/d62e41e49fc995e8d71378c7d55205c367913b03)<br>2026-10-13 | fix(分体键盘): 统一辅助栏与主键的扣缝宽度基准 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |
| [cecea4940](https://github.com/SandyYuR/fcitx5-android/commit/cecea4940bb5a5db67f91d6b20e00ee5c97877ea)<br>2026-10-17 | 修复(键盘): 按当前设置解析旧版滑动方向 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/keyboard/float-keyboard) |

## 语音

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [658806a54](https://github.com/SandyYuR/fcitx5-android/commit/658806a54b01f5ce51201c7772c5d3e27c4bc8bd)<br>2026-09-04 | fix(语音输入): AudioRecord 停止路径不再静默吞异常 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/voice-input) |
| [62a1d4466](https://github.com/SandyYuR/fcitx5-android/commit/62a1d44666ade468d2ed80e0b7a355d0c9581e22)<br>2026-09-04 | fix(语音输入): 回调主线程化与录音会话清理竞态 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/voice-input) |
| [43226d17f](https://github.com/SandyYuR/fcitx5-android/commit/43226d17f792b969449bbde957393449b22a1baf)<br>2026-09-04 | fix(语音输入): 队列切换丢包与重复就绪竞态；键盘组合态与断连崩溃兜底 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/voice-input) |
| [400f5523f](https://github.com/SandyYuR/fcitx5-android/commit/400f5523f3a8a4d5d3fd33f7d84b69ba55726f7e)<br>2026-09-17 | perf(语音): PCM 队列按音频时长定界并在过载时中止会话 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/voice-input) |
| [927b90e48](https://github.com/SandyYuR/fcitx5-android/commit/927b90e489b4c6c5c72ea2c350c42f1935e041df)<br>2026-09-20 | fix(日志): 语音与剪贴板原文不再写入 release 日志 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/voice-input) |
| [b68106936](https://github.com/SandyYuR/fcitx5-android/commit/b6810693620e391fca1bfb498ae10b29efda9fc2)<br>2026-10-21 | fix(语音输入): 提交结束时最后一条 partial | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/voice-input) |

## 工具栏

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [1ee554aa8](https://github.com/SandyYuR/fcitx5-android/commit/1ee554aa8342d92e882b3b49ad9bceb74abd6dfd)<br>2026-09-04 | fix(顶栏按钮面板): 拖拽期间不再整体重建顶栏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [adaebe8eb](https://github.com/SandyYuR/fcitx5-android/commit/adaebe8eba6e341275dcebb75644eb1918981db7)<br>2026-09-04 | fix(顶栏按钮面板): 分区去重与拖拽结果及时持久化 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [00d1baf41](https://github.com/SandyYuR/fcitx5-android/commit/00d1baf412ec5e63098c6fa0464bc466c8d1316e)<br>2026-09-04 | fix(顶栏): InputView 销毁时清空单例回调，消除泄漏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [c286a62fd](https://github.com/SandyYuR/fcitx5-android/commit/c286a62fd399c3a4f252f8f9b95fc7f56e0f9989)<br>2026-09-04 | perf(顶栏): 辅助栏仅在位置与内容变化时重排 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [4eb18ea2b](https://github.com/SandyYuR/fcitx5-android/commit/4eb18ea2bc1400e6a16d93397c5f480e79dd7237)<br>2026-09-04 | fix(顶栏): ButtonsBarUi 以稳定 viewType 复用并去除 bind 自刷新 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [23d9d88b7](https://github.com/SandyYuR/fcitx5-android/commit/23d9d88b74c3b71c313d30229659245aa5b8e6a4)<br>2026-09-04 | fix(顶栏按钮面板): 视图树随 InputView 生命周期绑定 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [83708a684](https://github.com/SandyYuR/fcitx5-android/commit/83708a684a3eeca8fc6d757bc47f9ba8d02636c8)<br>2026-09-13 | fix(工具栏): 恢复密码框隐藏策略 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [9be829bb4](https://github.com/SandyYuR/fcitx5-android/commit/9be829bb48a97cd16a9dabcd15b3fc6e48a4ed5b)<br>2026-09-13 | fix(工具栏): 修复数字行文字和中央按钮偶发空白 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [245e4bf1d](https://github.com/SandyYuR/fcitx5-android/commit/245e4bf1ddae76342379d0d23da0334cabd5244b)<br>2026-09-13 | feat(工具栏): 移除长按状态区按钮进入的 Kawaii Bar 可拖动编辑面板 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [cefa4bc78](https://github.com/SandyYuR/fcitx5-android/commit/cefa4bc7870aa40faed65422d07f916bf297430b)<br>2026-09-14 | fix(工具栏): 修复数字行单测读取基类不存在的 textSize | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |
| [e8827f0a7](https://github.com/SandyYuR/fcitx5-android/commit/e8827f0a7fca4fdd60b425979866f156a91171bd)<br>2026-10-11 | fix(工具栏): 移除密码框隐藏策略，恢复常规工具栏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/kawaii-bar) |

## 主题与字体

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [0a9da8360](https://github.com/SandyYuR/fcitx5-android/commit/0a9da83604e628e06c00b5b5c55a44852c12777c)<br>2026-09-04 | fix(图标主题): 选取文件不再用 DISPLAY_NAME 直接拼路径 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [5b36265c3](https://github.com/SandyYuR/fcitx5-android/commit/5b36265c3d20e6d0f88824aa07c4f5ad2b543896)<br>2026-09-04 | fix(图标主题): 导入的异常捕获放宽到 Throwable | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [41de06215](https://github.com/SandyYuR/fcitx5-android/commit/41de06215a89bcfc2a01e0862d594a6146f22ee6)<br>2026-09-04 | test(主题): 修正 ThemeSerializationTest 与当前实现的偏差 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [13c840815](https://github.com/SandyYuR/fcitx5-android/commit/13c840815ca07d3752fee1ea9f728f3c1ea82f7a)<br>2026-09-04 | perf(主题): refreshThemes 移到 IO 线程 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [f166d5098](https://github.com/SandyYuR/fcitx5-android/commit/f166d5098e789dfabd3bfa3ca0835d8deeadad6f)<br>2026-09-04 | perf(图标主题): IconThemeManager 初始化移出主线程 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [716f1c1f9](https://github.com/SandyYuR/fcitx5-android/commit/716f1c1f9547628d5bf06160699a833e7fbe65e3)<br>2026-09-04 | perf(主题): 壁纸解码移出主线程且只解一次 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [476327c8a](https://github.com/SandyYuR/fcitx5-android/commit/476327c8ac54437c9b56389f9c66575ff6633807)<br>2026-09-04 | perf(图标主题): 导入导出有界读取并后台化 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [4168a9519](https://github.com/SandyYuR/fcitx5-android/commit/4168a951938f71cc2ed7b44ed969ba4a5fee9b4f)<br>2026-09-04 | perf(输入法服务): 字体刷新标志读取后即清，不再全量重建组件树 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [4019829c2](https://github.com/SandyYuR/fcitx5-android/commit/4019829c266334d6b32808e529886f49d298f179)<br>2026-09-04 | fix(图标主题): ZIP 导入流式处理并设条目与总量上限 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [36af5144b](https://github.com/SandyYuR/fcitx5-android/commit/36af5144bfc092f41b09c44bef82c117e2d24202)<br>2026-09-06 | fix(发布): 修复 Play 商店图标失效链接 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [7ca3c5411](https://github.com/SandyYuR/fcitx5-android/commit/7ca3c541193a9714c87a928a1782ce3d375c0b29)<br>2026-09-13 | fix(状态区): 状态区按钮长按按动作分发，修复「亮色/暗色主题」长按无效 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [13e490aa2](https://github.com/SandyYuR/fcitx5-android/commit/13e490aa25c187e1de8684c789dcedf6fe9b1151)<br>2026-09-17 | perf(主题): 主题包读取与解压加限额并校验条目路径 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [98df9d060](https://github.com/SandyYuR/fcitx5-android/commit/98df9d060c4907405a108197a2312ea1f6235a7b)<br>2026-09-17 | perf(工具栏): 自定义图标解析结果加 ConstantState 缓存 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [d1d4eba08](https://github.com/SandyYuR/fcitx5-android/commit/d1d4eba088533bf3ef240559ea60db444ab235e1)<br>2026-09-20 | fix(工具栏): 自定义图标归一到标准尺寸，修复按钮左右空隙 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [91afccc3c](https://github.com/SandyYuR/fcitx5-android/commit/91afccc3c6092e9ed94e646869fedd8c6c0e8287)<br>2026-09-20 | perf(主题编辑器): 绘制期不再每帧分配渐变与画笔 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [3b4f4cd66](https://github.com/SandyYuR/fcitx5-android/commit/3b4f4cd660f172c549b0bb6133aab6fc8bfe8415)<br>2026-09-24 | fix(主题): styledColorOrDefault 属性缺失时不再崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [4a957b3af](https://github.com/SandyYuR/fcitx5-android/commit/4a957b3af92d928b9a24ca220b12be6e80ac8201)<br>2026-09-29 | feat(设置): 主题与候选栏的可见性改进 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [2d63c248f](https://github.com/SandyYuR/fcitx5-android/commit/2d63c248fcae61417cc8ebeddc365e870f0376f1)<br>2026-10-02 | feat(主题): 新增候选栏高亮圆角半径与高亮边距 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [d84e20ee9](https://github.com/SandyYuR/fcitx5-android/commit/d84e20ee90dc9fa330dfbf1fe161ba9c451b7334)<br>2026-10-03 | feat(工具栏): 新增工具栏大小百分比，栏高与图标可缩放，候选字号保持独立 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [e916245e5](https://github.com/SandyYuR/fcitx5-android/commit/e916245e53dddff9a0cfe980af6a4e2d5a86a5ad)<br>2026-10-04 | fix(字体设定): 字体与字号保存后即时生效，不再需要强杀输入法重开 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [3664ef31b](https://github.com/SandyYuR/fcitx5-android/commit/3664ef31b865aa1bc12d20099ce928d5ee92adcd)<br>2026-10-05 | fix(工具栏): 恢复100%高度下的图标大小 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [49c7c1694](https://github.com/SandyYuR/fcitx5-android/commit/49c7c1694b3fe1f0c2409e09839e07cee682201e)<br>2026-10-05 | fix(图标主题): 安装内置资源后刷新主题列表 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [43e16fb65](https://github.com/SandyYuR/fcitx5-android/commit/43e16fb6580748082cc56dad9cca87f470cb0ccc)<br>2026-10-10 | feat(随机主题): 增加随机配色评分与即时应用入口 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [01d0dfee3](https://github.com/SandyYuR/fcitx5-android/commit/01d0dfee391b4aac4bc88c01f7c7272696123690)<br>2026-10-10 | feat(主题): 编码区圆角与工具栏上方圆角接续 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [2ad85b939](https://github.com/SandyYuR/fcitx5-android/commit/2ad85b939e322d6a271eccbfb34f01add401da96)<br>2026-10-11 | feat(随机主题): 新装/升级默认常驻随机主题卡片 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [53038c225](https://github.com/SandyYuR/fcitx5-android/commit/53038c225a41372498c6aab2703c3ceab1e34b50)<br>2026-10-20 | 修复(主题): 修复 Android 10 及以下换主题崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [69fa5a9b8](https://github.com/SandyYuR/fcitx5-android/commit/69fa5a9b8cff32c855373b1fc496a605c0ef753d)<br>2026-10-21 | 重建 MD3 设置与首次引导，完善主题配色和返回动画 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [44d643158](https://github.com/SandyYuR/fcitx5-android/commit/44d6431583ec179a05e37e8d30c01ec19c2529b8)<br>2026-10-22 | 修复(主题): 避免缺失主题属性导致崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |
| [0015dced8](https://github.com/SandyYuR/fcitx5-android/commit/0015dced8f4101be1a00a33412291baed137a9b0)<br>2026-10-22 | 改进(主题): 预览键盘同时显示工具栏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/theme-editor) |

## 剪贴板与网络

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [11f011eea](https://github.com/SandyYuR/fcitx5-android/commit/11f011eea96c2a006ce8e1bf2a2669893ecf1bd2)<br>2026-09-04 | fix(剪贴板同步): 存储路径解码对裸 % 与畸形编码健壮 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [bf62a8de3](https://github.com/SandyYuR/fcitx5-android/commit/bf62a8de3edcb3a20aec71d6321a0effe7f83cc0)<br>2026-09-04 | fix(剪贴板): 暂存文件命名去重并与数据库同步 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [7097cd852](https://github.com/SandyYuR/fcitx5-android/commit/7097cd85271e817a2f315187cf5da653384ad269)<br>2026-09-04 | fix(剪贴板同步): 历史增量全部导入，游标不再跳过 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [7b8dbcf4e](https://github.com/SandyYuR/fcitx5-android/commit/7b8dbcf4e0d15a210b0b51635e593a3a4757eecf)<br>2026-09-04 | fix(剪贴板同步): 编辑器存储路径输入对裸 % 与畸形编码健壮 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [d23258574](https://github.com/SandyYuR/fcitx5-android/commit/d232585747bf558f6441cecf06e98e9e69cd17d9)<br>2026-09-04 | fix(剪贴板同步): 状态专用存储与锁内序列化 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [f6fa88e0e](https://github.com/SandyYuR/fcitx5-android/commit/f6fa88e0e132d09b6c0ec2fda20ddea13e86f010)<br>2026-09-04 | fix(剪贴板分词): 分词 job 跟随视图生命周期取消 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [0ca07d2bb](https://github.com/SandyYuR/fcitx5-android/commit/0ca07d2bb2eb080d42a4ddbc74efd674297b8c55)<br>2026-09-04 | perf(剪贴板): 图片粘贴移出主线程，去除 Thread.sleep | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [9447fa740](https://github.com/SandyYuR/fcitx5-android/commit/9447fa740ccf166c91627f5888ea60fdaf20e370)<br>2026-09-04 | perf(剪贴板同步): ClearURLs 规则与截图查询移出主线程 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [206aaa586](https://github.com/SandyYuR/fcitx5-android/commit/206aaa5869c26d25b0bc490ce7e898fc026ba287)<br>2026-09-04 | fix(剪贴板分词): 对长文本设上限 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [3bf2fcf86](https://github.com/SandyYuR/fcitx5-android/commit/3bf2fcf86a2687fcee3ce7efd5cf2aa9612cbf45)<br>2026-09-04 | perf(列表): 剪贴板分词与更新资产列表改为局部 notify | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [02d3da7e9](https://github.com/SandyYuR/fcitx5-android/commit/02d3da7e9a6c4155eaa2a652d34623a55a8dc631)<br>2026-09-04 | fix(剪贴板同步): 前台服务 dataSync 类型与空闲降频不停摆 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [ae4b8d776](https://github.com/SandyYuR/fcitx5-android/commit/ae4b8d77628115c8a9cb6fa8b626e9c89a6152ec)<br>2026-09-04 | perf(剪贴板同步): 接收过滤缓存化，不逐条重建 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [aba1c7904](https://github.com/SandyYuR/fcitx5-android/commit/aba1c7904a4a4974a0c54cbe270dca9dd4f0d433)<br>2026-09-04 | fix(剪贴板同步): 网络栈超时、取消语义与 PBKDF2 参数 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [77ad86772](https://github.com/SandyYuR/fcitx5-android/commit/77ad8677250515b4c51e49be53391978c0e2e3b0)<br>2026-09-04 | fix(剪贴板同步): 传输按上限流式读取，解码前先过滤尺寸 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [823039185](https://github.com/SandyYuR/fcitx5-android/commit/82303918536c1b03ee7316e127dec23f597938d1)<br>2026-09-04 | fix(剪贴板同步): 测试连接期间旋屏/返回不再崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [9cd45d79c](https://github.com/SandyYuR/fcitx5-android/commit/9cd45d79c7bf00d2983db4225c3fda0603b39255)<br>2026-09-04 | fix(剪贴板同步): 前台通知、状态落盘与网络栈超时/取消的审查收尾 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [b3612cb32](https://github.com/SandyYuR/fcitx5-android/commit/b3612cb3224f9299a7d40684abdb63b29ece4bfd)<br>2026-09-04 | test(剪贴板同步): base64 尺寸估算抽成纯函数并补 7 例单测 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [dc08c2a97](https://github.com/SandyYuR/fcitx5-android/commit/dc08c2a970484126508a40850ee22e85c5afc133)<br>2026-09-04 | fix(剪贴板同步): 前台状态更新幂等，避免重复投递通知与误清 foregroundActive | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [c9467e157](https://github.com/SandyYuR/fcitx5-android/commit/c9467e157bbd7ad8df8e33bc033c28660735eb98)<br>2026-09-05 | fix(剪贴板同步): 为 MainService 补充 Service.onBind 空实现 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [e3d69269b](https://github.com/SandyYuR/fcitx5-android/commit/e3d69269bc04687ea9850e244fdfbc16a17ffe81)<br>2026-09-11 | feat(剪贴板): 历史实时搜索（工具栏入口＋辅助栏结果） | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [871341b57](https://github.com/SandyYuR/fcitx5-android/commit/871341b570ac1908aa7a1abc7ab4c8737257f190)<br>2026-09-13 | fix(剪贴板): 修复 InputView 替换后搜索界面不再刷新 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [bf9a66adc](https://github.com/SandyYuR/fcitx5-android/commit/bf9a66adc0d6bd6885c4bdc4fbf68d03a91326d3)<br>2026-09-14 | docs(README): 补充剪贴板实时搜索与中央按钮行修复说明 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [4197dffe1](https://github.com/SandyYuR/fcitx5-android/commit/4197dffe17947686bb8cfd8045c2427dbee469c0)<br>2026-09-17 | perf(剪贴板同步): 健康检查、熄屏截图监听与连接生命周期 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [4c8b76ea5](https://github.com/SandyYuR/fcitx5-android/commit/4c8b76ea5f68a32b0e0d865ca97d639f6d11443b)<br>2026-09-17 | perf(剪贴板同步): 状态写入按 key 合并与文件名碰撞流式比较 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [17793f046](https://github.com/SandyYuR/fcitx5-android/commit/17793f046e55d03fc1368d7d648f420076e8824e)<br>2026-09-17 | perf(剪贴板): 历史搜索改 SQL 筛选并加防抖，裁剪只查 id | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [8aada6693](https://github.com/SandyYuR/fcitx5-android/commit/8aada6693848106bf27cfc9d2b356d5e0900d651)<br>2026-10-17 | 修复(剪贴板): 避免截图历史重复入库 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |
| [9d0110abf](https://github.com/SandyYuR/fcitx5-android/commit/9d0110abf6e95aee133816b7dd0c6f5ce1280d3b)<br>2026-10-17 | 修复(剪贴板): 合并指向同一图片的 URI 条目 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/clipboard-sync) |

## 布局与宏

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [2459a65e4](https://github.com/SandyYuR/fcitx5-android/commit/2459a65e407d97376c002a6bf4ac903b3d5011aa)<br>2026-09-04 | fix(布局解析): 布局 JSON 健壮性——非对象元素、缺 tap 宏键与 JsonNull | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [69e4580dd](https://github.com/SandyYuR/fcitx5-android/commit/69e4580dda14dc86686fa242767b26c64a16b8ba)<br>2026-09-04 | fix(布局分享): XZ 解压设置内存上限，挡住 GB 级字典预分配 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [08a6d4e93](https://github.com/SandyYuR/fcitx5-android/commit/08a6d4e934c3ce2d5446250450edcbf7258634f4)<br>2026-09-04 | fix(输入配置): 带注释的布局 JSON 解析不再静默回退 | 回退记录，不能将被回退的方案按标题列为现有功能；查当前主题说明。 [阅读文档](/features/editor/layout-editor) |
| [38b390136](https://github.com/SandyYuR/fcitx5-android/commit/38b3901366f81a234b760ec036325783a931f70e)<br>2026-09-04 | fix(布局编辑器): 旋屏重建的状态保存与拖拽结果传递 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [1c5a98aae](https://github.com/SandyYuR/fcitx5-android/commit/1c5a98aae3bea62f53aa688f8edf96e70b52f6c7)<br>2026-09-04 | fix(布局编辑器): 布局保存原子化且迁移按需执行 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [554de0f04](https://github.com/SandyYuR/fcitx5-android/commit/554de0f04728d4c12f06a8eb2f63f5fdfecd36b0)<br>2026-09-04 | fix(按键弹窗): dismissAllPopups 按 viewId 区分，双指打字不误清 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [48d56f791](https://github.com/SandyYuR/fcitx5-android/commit/48d56f791e03aeb3acddd013456256dfe6d24779)<br>2026-09-04 | fix(布局编辑器): 拖拽排除添加键并修正 FlowLayout 测量 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [873fccdd4](https://github.com/SandyYuR/fcitx5-android/commit/873fccdd49e2c45e3d4af6dd13001913edf1a7b8)<br>2026-09-04 | fix(顶栏): 删除自定义宏后按钮不再执行旧宏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [3b71f54cf](https://github.com/SandyYuR/fcitx5-android/commit/3b71f54cf5f54dcc48a61862df432826264c73a0)<br>2026-09-04 | perf(布局编辑器): 布局加载/解析/序列化/写入移出主线程 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [a5a81fc09](https://github.com/SandyYuR/fcitx5-android/commit/a5a81fc0924f6233625ae7ec702af462839fe214)<br>2026-09-04 | perf(布局编辑器): 行 chip 复用，局部刷新替代全行重建 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [8bb0b3472](https://github.com/SandyYuR/fcitx5-android/commit/8bb0b3472863b80d33e189930344e6b542a1b478)<br>2026-09-04 | perf(布局编辑器): 键盘预览走独立布局通道，不再清空全局缓存 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [9a53a38a5](https://github.com/SandyYuR/fcitx5-android/commit/9a53a38a5a638e5463cac3a37f44933b4f6a1f03)<br>2026-09-04 | fix(布局分享): 二维码长图分片数超限时可导回 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [1470576fb](https://github.com/SandyYuR/fcitx5-android/commit/1470576fbdf42ebda51206044952558cb5002e6f)<br>2026-09-04 | fix(布局编辑器): 草稿恢复补辅助栏与 profile 校验，旋转预览与拖拽索引兜底 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [0e5b884ff](https://github.com/SandyYuR/fcitx5-android/commit/0e5b884ff9d65ada62ee25ba8fbff287eba41ed0)<br>2026-09-05 | fix(宏编辑器): 应用动作选择器 id 与标签错位 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [238f61af3](https://github.com/SandyYuR/fcitx5-android/commit/238f61af3d135cc45813415ed8d356c51c0cb4d4)<br>2026-09-05 | fix(布局编辑器): 草稿改存私有文件，修复编辑按键时 TransactionTooLarge 崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [9a23ea277](https://github.com/SandyYuR/fcitx5-android/commit/9a23ea277385f7411792ce94e7d4ae67fb80b657)<br>2026-09-07 | fix(键盘): 宏 layer to 离开数字层时释放手动数字布局记忆 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [589df1877](https://github.com/SandyYuR/fcitx5-android/commit/589df1877df90e20d236d26c6908f55268c2a476)<br>2026-09-07 | fix(键盘): ?123 进数字盘记入层历史，BACK 空栈时释放手动数字布局 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [56e450b86](https://github.com/SandyYuR/fcitx5-android/commit/56e450b869a36b9942db1b49f6057cccadd37574)<br>2026-09-07 | fix(布局编辑器): 校验草稿快照文件名，阻断路径穿越 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [f0846c779](https://github.com/SandyYuR/fcitx5-android/commit/f0846c779341eac37894a0b6ba4ccc968565c310)<br>2026-09-08 | fix(布局编辑器): 拖拽松手后按键不归位，停在原地错位显示 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [072060126](https://github.com/SandyYuR/fcitx5-android/commit/07206012646356ccddf296105e5a2b3bb548a167)<br>2026-09-14 | fix(工具栏): 中央按钮行改为确定性布局，修复悬浮切换后整行空白 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [a5e0c5d82](https://github.com/SandyYuR/fcitx5-android/commit/a5e0c5d82f388be33a6add531539b5b80ca1f6a5)<br>2026-09-15 | feat(内置资源): 内置群友贡献的布局/主题/图标主题并调整主题默认值 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [2b93023a0](https://github.com/SandyYuR/fcitx5-android/commit/2b93023a07daeb183262064176425566f482907d)<br>2026-09-15 | feat(内置资源): 更新 9+18+26keys 布局为大同版并支持内置资源内容更新 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [08e48eacd](https://github.com/SandyYuR/fcitx5-android/commit/08e48eacd9ebc64f0b459e8bd5fd222a8fff12c8)<br>2026-09-19 | feat(语言键与宏操作): 长按语言键弹Rime方案选单，宏应用操作新增切换输入法与方案选单 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [6bdf2a860](https://github.com/SandyYuR/fcitx5-android/commit/6bdf2a8601ad18da000ea0a292f34b9926a2540c)<br>2026-09-20 | feat(内置资源): 更新大同与 Sandy 布局，去高度覆盖并接入方案选单宏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [2f9d84cf3](https://github.com/SandyYuR/fcitx5-android/commit/2f9d84cf3955ac12250c0f2ea28fa359642aa8c5)<br>2026-09-20 | feat(候选词): 上滑弹出选字窗提交单字 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [caef15477](https://github.com/SandyYuR/fcitx5-android/commit/caef154772f0f3882282fea6bdbebe74a3bdcd3d)<br>2026-09-20 | fix(键盘布局编辑): 保存基线快照前移，返回键改走 OnBackPressedDispatcher | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [afc57f4b1](https://github.com/SandyYuR/fcitx5-android/commit/afc57f4b10e678197fa72d1035c83f39d6020df3)<br>2026-09-28 | fix(布局编辑器): 子模式下拉框补「默认」项，修复 rime 默认布局无法编辑 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [f84084c34](https://github.com/SandyYuR/fcitx5-android/commit/f84084c34585c5ac4160b61e93134f9275b23534)<br>2026-09-28 | feat(键盘): 空格键支持在布局编辑器配置划动动作 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [4be7dc557](https://github.com/SandyYuR/fcitx5-android/commit/4be7dc5570d9a4f1f93d248b6937d338dfc7d2ed)<br>2026-09-29 | feat(布局编辑器): 按键类型下拉框加本地化中文说明 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [792099e71](https://github.com/SandyYuR/fcitx5-android/commit/792099e71e54f4537344573d4bba75bf35301293)<br>2026-09-29 | chore(布局): 26 键字母布局移除宏键多余的显示文本 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [f2dc67ceb](https://github.com/SandyYuR/fcitx5-android/commit/f2dc67ceb498c8c63d9e5977eacb5909cc1d8ea5)<br>2026-09-30 | feat(布局): AlphabetKey 显示文本优先，并清理 26 键字母层的冗余显示文本 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [3b85496ad](https://github.com/SandyYuR/fcitx5-android/commit/3b85496ad93b9900d1c7ac7c49e4efbfe2a653de)<br>2026-09-30 | fix(布局编辑器): 子模式下拉框「默认」项不再被当成越界 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [1464ff594](https://github.com/SandyYuR/fcitx5-android/commit/1464ff5947d6468257020f5fc165adfbd6faaaea)<br>2026-09-30 | feat(设置): 弹出字符设定移入键盘，分组小字与「键盘布局自定义」定稿 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [fbed25003](https://github.com/SandyYuR/fcitx5-android/commit/fbed250035c4d4ec8e1f48f79d25caa274902260)<br>2026-09-30 | feat(文本编辑): 新增 Foxy 风格文本编辑布局配置 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [06364f608](https://github.com/SandyYuR/fcitx5-android/commit/06364f608f24d15e06623d83b7d257ce9f3ddd37)<br>2026-10-03 | feat(分体键盘): 分体布局可按子布局单独配置，新增空白占位键与中缝对齐开关 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [d156052b2](https://github.com/SandyYuR/fcitx5-android/commit/d156052b25aae54f4219f799416c9475e1a19128)<br>2026-10-07 | feat(键盘): 支持同一按键分别配置上下划动宏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [4f0d6fb8c](https://github.com/SandyYuR/fcitx5-android/commit/4f0d6fb8cf53cd0c1f9666e3095ed70d634981ab)<br>2026-10-08 | fix(键盘): 修复 MacroKey 上下划动标签迁移与动作触发 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [b94106da3](https://github.com/SandyYuR/fcitx5-android/commit/b94106da34fa534275ab81a3d5832fc1064704a5)<br>2026-10-08 | fix(弹出键盘): 多字符候选改用 CommitAction 提交，避免被 fcitx 静默丢弃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [598897b74](https://github.com/SandyYuR/fcitx5-android/commit/598897b7488644c48357ae6f868cebbbfebf5efc)<br>2026-10-08 | fix(弹出键盘): 校验弹出文本后选择提交方式 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [ed38a31b0](https://github.com/SandyYuR/fcitx5-android/commit/ed38a31b070072740f61d8e4f98ac4f5b0891685)<br>2026-10-09 | fix(键盘标签): 统一双标签高度回退布局 | 回退记录，不能将被回退的方案按标题列为现有功能；查当前主题说明。 [阅读文档](/features/editor/layout-editor) |
| [d786dd789](https://github.com/SandyYuR/fcitx5-android/commit/d786dd789e39c487905c1787023b21d9b0afbccd)<br>2026-10-10 | fix(弹出菜单): 跟随主题使用整体背景色 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [0ca7903ee](https://github.com/SandyYuR/fcitx5-android/commit/0ca7903ee5f9820eaff098f0685487a37079b018)<br>2026-10-12 | fix(数字盘): 显式切回数字盘重新武装被 dismiss 的布局覆盖 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [70298bcbe](https://github.com/SandyYuR/fcitx5-android/commit/70298bcbebc39eae1fee9d6a568ff1c97c674766)<br>2026-10-12 | feat(密码框): 固定 default 布局回退链与空格键「（密码输入）」提示 | 回退记录，不能将被回退的方案按标题列为现有功能；查当前主题说明。 [阅读文档](/features/editor/layout-editor) |
| [9310fa02d](https://github.com/SandyYuR/fcitx5-android/commit/9310fa02dee8ba4fd5bd12bb6c962ae377b29647)<br>2026-10-13 | fix(键盘布局): 分体复用辅助栏并沿用方案配置键 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [1165ebd90](https://github.com/SandyYuR/fcitx5-android/commit/1165ebd90e4700f88b2384bff7aa4edb37527496)<br>2026-10-13 | feat(内置布局): 更新 Sandy 九键与数字分体排列 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [1713d6130](https://github.com/SandyYuR/fcitx5-android/commit/1713d613030d3d009ca227f04eb3eb6b62f29a69)<br>2026-10-18 | 改进(界面): 优化布局管理、编辑器配色与工具栏分节 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [92fd34ce0](https://github.com/SandyYuR/fcitx5-android/commit/92fd34ce060cb4625d99066443d294c453c4c693)<br>2026-10-18 | 修复(popup): 让弹出符号经 Rime 方案处理 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |
| [5fb6bca34](https://github.com/SandyYuR/fcitx5-android/commit/5fb6bca3415107ebe088b98751109f5b4f644972)<br>2026-10-22 | 优化键盘布局编辑器选择器 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/editor/layout-editor) |

## 更新下载

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [6f1218e0c](https://github.com/SandyYuR/fcitx5-android/commit/6f1218e0c303371f3f5957aaa1b486d7e12a9150)<br>2026-09-04 | fix(更新器): 镜像 replacement 的非法组引用不再崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/update-checker) |
| [b7cacda69](https://github.com/SandyYuR/fcitx5-android/commit/b7cacda698910e3be361097b0d59f3f5bc5bf105)<br>2026-09-04 | perf(更新器): 下载校验移到 IO 线程并为 SHA-256 加缓存 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/update-checker) |
| [c80eecac5](https://github.com/SandyYuR/fcitx5-android/commit/c80eecac5fedfb9bdac0e13acdab23917ce861b5)<br>2026-09-04 | fix(更新器): 轮询随 onPause 停止，hosts 抓取复用与写回收敛 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/update-checker) |
| [1ad9684a7](https://github.com/SandyYuR/fcitx5-android/commit/1ad9684a7e789c33a856090c4495b8c3aba21c79)<br>2026-09-04 | perf(更新器): 下载复用 OkHttpClient 实例 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/update-checker) |
| [7b4dfe803](https://github.com/SandyYuR/fcitx5-android/commit/7b4dfe80365a614cf88e5fa76a88f1dd3898dc42)<br>2026-09-04 | perf(更新器): MediaStore 查询限定范围并按时间排序 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/update-checker) |

## 数据与备份

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [d9c33bea0](https://github.com/SandyYuR/fcitx5-android/commit/d9c33bea0634ab1da9730948a90569015b1b0821)<br>2026-09-04 | fix(数据迁移): 备份时间戳含毫秒且不再删子模式 displayText | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/backup-restore) |
| [eddcad9a8](https://github.com/SandyYuR/fcitx5-android/commit/eddcad9a85212b69d5706b0de91414a4428021e6)<br>2026-09-04 | fix(备份): 导入安全关闭 Room，不再覆盖 WAL 与降级清库 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/backup-restore) |
| [5cc57c0da](https://github.com/SandyYuR/fcitx5-android/commit/5cc57c0dac774b7d2511bedcfc1ee2cd3296c783)<br>2026-09-08 | fix(设置): 用户数据目录入口改用应用 DocumentsProvider | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/backup-restore) |
| [410323472](https://github.com/SandyYuR/fcitx5-android/commit/41032347259416bd0292acaf2c30de9624e76c2e)<br>2026-09-20 | fix(数据目录): descriptor 改为原子写入，避免异常退出后全量重同步 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/backup-restore) |
| [212e67b10](https://github.com/SandyYuR/fcitx5-android/commit/212e67b108246268a6ce45edeb94e5f10d303593)<br>2026-10-13 | feat(数据目录): 支持外部 Rime 目录与完整备份恢复 (#3) | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/backup-restore) |

## 局域网编辑

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [9ec852a90](https://github.com/SandyYuR/fcitx5-android/commit/9ec852a90d0cfb538cb17ca00bf6b3172f8be993)<br>2026-09-04 | fix(网络编辑桥): 有界线程池、请求体上限与连接数限制 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/online-editor) |
| [db25adcb1](https://github.com/SandyYuR/fcitx5-android/commit/db25adcb1d424c07109fb1fbe9eabac6eb98f8e0)<br>2026-09-10 | fix(网络编辑桥): 修复局域网无法访问（F7 线程池饥饿回归） | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/online-editor) |

## 候选与输入

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [a5e9140e5](https://github.com/SandyYuR/fcitx5-android/commit/a5e9140e5d35867d248c48da8e38cf7bad3f0353)<br>2026-09-04 | fix(键盘): 按键事件语义——录音结束、候选误提交与长按连删 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [165fa2888](https://github.com/SandyYuR/fcitx5-android/commit/165fa28889e7ebc5f23f78bc31024dc94dce6b2a)<br>2026-09-04 | fix(键盘): 缓存行的文本缩放重放与空格滑动开关 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [0debfa333](https://github.com/SandyYuR/fcitx5-android/commit/0debfa333e8ae1e6a5576bf8edfc0f17dcba0418)<br>2026-09-04 | perf(按键弹窗): 长按弹窗缓存判定前置，不再每次同步读盘 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [121badf54](https://github.com/SandyYuR/fcitx5-android/commit/121badf54b35c46ca36b7142d73cfbc9c5abc9fe)<br>2026-09-04 | perf(候选栏): 增量刷新，去除递归 view.post | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [ffc6e4c1a](https://github.com/SandyYuR/fcitx5-android/commit/ffc6e4c1ac1d5c3c1ec23bc4f96ec73fc7efd0c8)<br>2026-09-04 | fix(输入法服务): 事件流断连兜底只捕获 FcitxDisconnectedException | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [73497f6ea](https://github.com/SandyYuR/fcitx5-android/commit/73497f6eaf83754fb49a9a4702892f7691d8ea56)<br>2026-09-05 | perf(候选栏): 前后缀结构 diff 替代 notifyDataSetChanged 全量重绑 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [3e24e1a4d](https://github.com/SandyYuR/fcitx5-android/commit/3e24e1a4d1ecadf3cd2cbe53db0e95dd4b0e9dec)<br>2026-09-05 | perf(候选栏): 帧延迟、宽度预计算与每键分配削减 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [85971c94f](https://github.com/SandyYuR/fcitx5-android/commit/85971c94f40b37b1d2aae915f03101e9d6878b39)<br>2026-09-05 | perf(输入链路): Phase 0 性能埋点（androidx.tracing） | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [c5cff57f0](https://github.com/SandyYuR/fcitx5-android/commit/c5cff57f09825d56fa96c580ba99d82e420e14dc)<br>2026-09-05 | fix(候选栏): onLayoutCompleted 中消费 pendingEnsureVisible | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [26fc14546](https://github.com/SandyYuR/fcitx5-android/commit/26fc1454669c4dbf89cdeb4561b62b22ffa4e274)<br>2026-09-05 | feat(键盘): 语言键改为发送独立 Shift 敲击，长按弹系统输入法菜单 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [17d249a4f](https://github.com/SandyYuR/fcitx5-android/commit/17d249a4f7076aa089b38f348462e69e15c3fce6)<br>2026-09-05 | feat(设置): 主设置页"输入法"改为"中州韵设置"，直达 rime 配置页 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [0a95e9174](https://github.com/SandyYuR/fcitx5-android/commit/0a95e91745a4dd8ba8c0d7cbd5969ba057262432)<br>2026-09-05 | refactor(设置): 清理其余通往输入法列表页的入口 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [2c1b9cb55](https://github.com/SandyYuR/fcitx5-android/commit/2c1b9cb5546a7d4e2c34a433638ea354826c6037)<br>2026-09-07 | fix(rime): IME 退出路径跳过 Rime 全量用户数据同步，修复切回输入法时键盘冻结数秒 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [d1e5bafcd](https://github.com/SandyYuR/fcitx5-android/commit/d1e5bafcd858f10253efa7fce9318426ac1bb26b)<br>2026-09-09 | fix(候选栏): 推迟 onLayoutCompleted 内的候选窗口跟随更新，修复双击斜杠崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [a4d2f13f7](https://github.com/SandyYuR/fcitx5-android/commit/a4d2f13f74f124c602ae0a22c9dd723382f1ff5a)<br>2026-09-10 | ci(release): 发布描述改为「仅可使用Rime输入方案（插件已合并）」 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [a787dfd84](https://github.com/SandyYuR/fcitx5-android/commit/a787dfd84cddc5358ee720ae3a85ce4d16387ef7)<br>2026-09-11 | fix(输入): 滑动空格箭头键在文本边界不再逃逸输入框焦点 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [5dbe1f6cf](https://github.com/SandyYuR/fcitx5-android/commit/5dbe1f6cf253a1596d07e5909d99cabc1109395e)<br>2026-09-14 | fix(候选栏): 高亮重绑按结构位移后的坐标解析，修复双高亮 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [5388abc21](https://github.com/SandyYuR/fcitx5-android/commit/5388abc213d2f3b836eb6de5649e5a0d7c6eea50)<br>2026-09-17 | perf(输入): 浮动键盘缩放按帧合并提交 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [12aabd502](https://github.com/SandyYuR/fcitx5-android/commit/12aabd50213910bd1b196170065528c085b7d30d)<br>2026-09-17 | perf(候选栏): 展开面板 offset 去重、DiffUtil 内容比较与取用时刻全局下标修复 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [bce1ec0f0](https://github.com/SandyYuR/fcitx5-android/commit/bce1ec0f08ecca77b9ae85718c4a7737cb64f2e3)<br>2026-09-17 | feat(退格保护): 长按删空编码后持续拦截退格直到抬指 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [b5ea52223](https://github.com/SandyYuR/fcitx5-android/commit/b5ea522232f2bb82fa806d02940e382dc2d47ba1)<br>2026-09-18 | feat(退格保护): 保护段内被吞的重复退格不再触发按键反馈 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [87ed7f06b](https://github.com/SandyYuR/fcitx5-android/commit/87ed7f06ba34ac1ae0ac15344516277ddd4bdc09)<br>2026-09-20 | feat(候选词): 手势改为按住后滑动，上滑选字、下滑呼出操作菜单 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [8a1493321](https://github.com/SandyYuR/fcitx5-android/commit/8a1493321b425650cd468d1005969ee6924157fa)<br>2026-09-20 | feat(Rime): 不再预置任何输入方案，也不附带 essay.txt | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [815aa4c37](https://github.com/SandyYuR/fcitx5-android/commit/815aa4c3731a2765480307cf594f6dcc97400fa5)<br>2026-09-20 | fix(输入): 候选分页模式在无活动输入上下文时崩溃 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [acb1d3b91](https://github.com/SandyYuR/fcitx5-android/commit/acb1d3b915eaaa3606aa649a8938ae440aac07ed)<br>2026-09-21 | feat(键盘): 退格键长按后上滑清空输入框 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [0ae646819](https://github.com/SandyYuR/fcitx5-android/commit/0ae646819d9f13f24c8e31703afb6543027c3b2c)<br>2026-09-29 | feat(键盘): 空格键长按行为新增「重复输入空格」 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [9862e7b8e](https://github.com/SandyYuR/fcitx5-android/commit/9862e7b8ebfcca3e8bc1ee088f10518c80563c88)<br>2026-10-02 | fix(键盘): 浮动/分体切换与拖动时按键挤成一行 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [2501375fc](https://github.com/SandyYuR/fcitx5-android/commit/2501375fcd17d34936b49cfb13ab74de8220c051)<br>2026-10-02 | feat(退格手势): 合成中上滑改为清编码区(Esc)，提示条显示Esc | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [584bddaa2](https://github.com/SandyYuR/fcitx5-android/commit/584bddaa25b1cec3957c1627ba514b819936d464)<br>2026-10-02 | fix(辅助选择栏): 自定义按键按行高百分比分配高度 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [18619aefc](https://github.com/SandyYuR/fcitx5-android/commit/18619aefc52b25442f154452d9b725f3b612e820)<br>2026-10-04 | fix(候选栏): 高亮改为三层结构，文字与高亮边框间距恒定且不再上下贴边 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [debae6ded](https://github.com/SandyYuR/fcitx5-android/commit/debae6ded9e091f561b07fcea36a56eff762031d)<br>2026-10-07 | fix(候选栏): 注释宽度按实际 span 测量 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [751480cd3](https://github.com/SandyYuR/fcitx5-android/commit/751480cd33aeb576acbeb00f0f7970fc76f86dd0)<br>2026-10-07 | feat(候选栏): 支持长候选横向滚动 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [2609dc2dd](https://github.com/SandyYuR/fcitx5-android/commit/2609dc2dd7d9fafe7c987b56bb096ecd0041f40e)<br>2026-10-08 | fix(按键兼容): 仅在 vivo OriginOS 启用兼容开关 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [098ec596d](https://github.com/SandyYuR/fcitx5-android/commit/098ec596dfa6793ae98a38aef4b9c8adaf33eebc)<br>2026-10-08 | fix(音节选择栏): 支持超出六项滚动选择 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [897ece50c](https://github.com/SandyYuR/fcitx5-android/commit/897ece50cb3b74266b0d1e75a6fa1f891bcd871a)<br>2026-10-09 | fix(按键兼容): 小米设备默认开启兼容开关 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [635d3aa23](https://github.com/SandyYuR/fcitx5-android/commit/635d3aa2337189a6e75fc1ee2f828e3f76a809f6)<br>2026-10-10 | feat(候选栏): 增加候选项序号角标开关 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [4eae65394](https://github.com/SandyYuR/fcitx5-android/commit/4eae653942f38a10e350772e6f5c88023c70c3e6)<br>2026-10-10 | fix(键盘标签): 方向性划动标签与触发方向脱离「标点位置」 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [fdb765a30](https://github.com/SandyYuR/fcitx5-android/commit/fdb765a3072c306c19f86f44a221ef6e52113bf3)<br>2026-10-11 | feat(候选栏): 序号角标增加内容选项，可从0起或跟随方案选词标签 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [393ba918c](https://github.com/SandyYuR/fcitx5-android/commit/393ba918cfb06dceb4500c2856e699a05f247498)<br>2026-10-12 | fix(密码框): 无活动输入法时 InputMethodStatus 空指针崩溃防护 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [a714feff7](https://github.com/SandyYuR/fcitx5-android/commit/a714feff77fd79082807b9c7141e0515c8c83f73)<br>2026-10-13 | fix(编码区): 对称保留圆角空间并修复长文本越界 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [b4b09b4af](https://github.com/SandyYuR/fcitx5-android/commit/b4b09b4af31ea088c621c6ab4d660941fdacdf09)<br>2026-10-16 | 修复(输入): 限制最近记录并优化光标日志 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [1c05d8bc3](https://github.com/SandyYuR/fcitx5-android/commit/1c05d8bc3a31ea3851f017bb03d54cf16c919b61)<br>2026-10-21 | 修复(输入): 空格键长按切换 Rime 方案 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |
| [e0e3bbb2a](https://github.com/SandyYuR/fcitx5-android/commit/e0e3bbb2abeb69ea37517c02e1a243a6607589f2)<br>2026-10-22 | 新增(输入): 浮动键盘移动把手自动隐藏 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/candidate-window) |

## 旧引擎与集成

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [c2fb5166e](https://github.com/SandyYuR/fcitx5-android/commit/c2fb5166eb8fee74f1828a3239ebcabb5a4b8f55)<br>2026-09-04 | fix(引擎守护): FcitxDaemon 不再用 CancellationException 表示断连 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [7bc89b995](https://github.com/SandyYuR/fcitx5-android/commit/7bc89b995b4fc28108dbbaaa94c053e9986f8c86)<br>2026-09-05 | feat(rime): 将 fcitx5-rime 并入主 APK（librime 静态链接 + rime-data 资源 + opencc 软链） | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [870ab1c86](https://github.com/SandyYuR/fcitx5-android/commit/870ab1c863f785e2601004ca0f2a83ee0a729173)<br>2026-09-05 | fix(引擎): 首次启动时预置 rime 为默认启用输入法 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [17b38c717](https://github.com/SandyYuR/fcitx5-android/commit/17b38c717592df6c6ac9302c2f754b10cc60790c)<br>2026-09-06 | chore(引擎): 更新 librime 版本元数据至 1.17.0-3cbe4af | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [686c47c79](https://github.com/SandyYuR/fcitx5-android/commit/686c47c7906e30093f33135dde4fbf282a8a590b)<br>2026-09-07 | ci: 恢复 rime-only nightly release | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [55a913f2c](https://github.com/SandyYuR/fcitx5-android/commit/55a913f2c72bca3b488be12ab5f4401c57fd2601)<br>2026-09-08 | docs(README): 重写 rime-only 功能说明与相对 fxliang 的改动概览 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [36ebb39d9](https://github.com/SandyYuR/fcitx5-android/commit/36ebb39d9f4dbb1517c26405f05bba1b742bb4b8)<br>2026-09-10 | chore(引擎): 更新 librime 版本元数据至 1.17.0-35f23e9（streaming_chord + userdict 外部更新修复） | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [234c3cfcd](https://github.com/SandyYuR/fcitx5-android/commit/234c3cfcd70665018b02a46d8daa404b82f18352)<br>2026-09-17 | fix(引擎): 修复词典重建时 table.bin 头部损坏导致的 invalid metadata | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [1539e99bd](https://github.com/SandyYuR/fcitx5-android/commit/1539e99bdf4e1ebf03c35c218b324613ecfbd8a4)<br>2026-09-20 | chore(引擎): prebuilt 指针更新至 6b5b2ee6，修复忘记词汇连带删除同音词 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [8843b8581](https://github.com/SandyYuR/fcitx5-android/commit/8843b858133f8bc801a8cfec09424b304189f49d)<br>2026-09-20 | fix(引擎启停): 状态机可停 STARTING、启动失败必收敛、停止不再阻塞主线程 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [f8119628b](https://github.com/SandyYuR/fcitx5-android/commit/f8119628ba9674966c0ded7acfd3af73eec0ac62)<br>2026-09-21 | feat(引擎): prebuilt 指针更新至 a1865519，rewrite 滤镜跟进至 abbdacea | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [746271dfd](https://github.com/SandyYuR/fcitx5-android/commit/746271dfd31420c803abc95b55b3930b522afe99)<br>2026-09-21 | chore(引擎): fcitx5-rime 指针更新至 fc3f98b，移除辅助栏"清除"按钮 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [a560aabe1](https://github.com/SandyYuR/fcitx5-android/commit/a560aabe17d2710d1d54a6f654c20a07a26ee39f)<br>2026-09-24 | feat(引擎): 引擎 pin 前移至 ef1a16aa，rewrite 补丁跟进 PR #1232 至 8530499b | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [f44913fc8](https://github.com/SandyYuR/fcitx5-android/commit/f44913fc8b563a018e71598ebac4cd869b7b61c6)<br>2026-09-24 | refactor(jni): 统一 JNI 数组迭代写法并修正引用释放 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [177f37cc7](https://github.com/SandyYuR/fcitx5-android/commit/177f37cc79efbfb538ab981be1654bf637a85c48)<br>2026-09-24 | chore(引擎): fcitx5 core 指针更新至 1e005518 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [819f8fbc8](https://github.com/SandyYuR/fcitx5-android/commit/819f8fbc8446e08faed16718beb02c2b79dfe3f5)<br>2026-09-24 | chore(引擎): fcitx5 core 指针更新至 27cca6e0 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [8d5adb7d8](https://github.com/SandyYuR/fcitx5-android/commit/8d5adb7d893cf0bc675d5a6502a655fb71735560)<br>2026-09-24 | chore(引擎): fcitx5-rime 指针更新至 dcd6162，版本号提升至 5.1.17 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [e7e324624](https://github.com/SandyYuR/fcitx5-android/commit/e7e324624b6a076fd9e1c5dfacf90d5eed4c8a51)<br>2026-09-26 | revert(引擎启停): 回退 8843b858 状态机改动以排查卡键回归 [skip ci] | 回退记录，不能将被回退的方案按标题列为现有功能；查当前主题说明。 [阅读文档](/legacy/fcitx/maintainer/) |
| [b37267e54](https://github.com/SandyYuR/fcitx5-android/commit/b37267e543b2e9a5ee9bf46abcd80be116eeb324)<br>2026-09-28 | chore(引擎): prebuilt 指针更新至 1126616c，修复冷启动全量部署误判 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [babd5c139](https://github.com/SandyYuR/fcitx5-android/commit/babd5c139449a14bf02ea4c4bc83456d2e7154d2)<br>2026-09-28 | chore(引擎): fcitx5-rime 指针更新至 9bf94e6，部署期按键与提示修复 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [a1ffb91f2](https://github.com/SandyYuR/fcitx5-android/commit/a1ffb91f2bbfc129ec58b7b779a87c053c55bd13)<br>2026-09-29 | feat(布局): MacroKey 显示文本优先，并修复 JNI 非法 UTF-8 崩溃 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [4b560f0d9](https://github.com/SandyYuR/fcitx5-android/commit/4b560f0d99ef8bcca0ac6fd4bc02d4af350b2c5c)<br>2026-10-02 | chore(引擎): fcitx5-rime 指针更新至 107502d，过滤非法音节 tab 乱码 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [37aef03a1](https://github.com/SandyYuR/fcitx5-android/commit/37aef03a18c02a7ab8f55e5c90d78ebbb16aad5a)<br>2026-10-05 | docs(README): 文档链接切换到文档站，引擎来源与提交计数更新到当前 | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |
| [35cfe7f7c](https://github.com/SandyYuR/fcitx5-android/commit/35cfe7f7c79d5582025db8b1023b67cf9e44aa11)<br>2026-10-08 | Merge pull request #2 from Ocyss/fx-rime-only | 旧 Fcitx 阶段实现或依赖记录；不作为当前直连库版本和运行链路依据。 [阅读文档](/legacy/fcitx/maintainer/) |

## 设置与引导

| 提交 / 日期 | 代码改动 | 文档对照与处理 |
| --- | --- | --- |
| [dcc1046f5](https://github.com/SandyYuR/fcitx5-android/commit/dcc1046f5b47351fc2fe5750020a502c5a0c26a2)<br>2026-09-05 | refactor(设置): 移除拼音/码表 UI 与插件相关入口 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/settings-appearance) |
| [47ee56fdd](https://github.com/SandyYuR/fcitx5-android/commit/47ee56fddc257a082cbf52fb4ef31451bf29fb6b)<br>2026-09-05 | refactor(组件): 移除 quickphrase 快速短语组件 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/settings-appearance) |
| [82090abfb](https://github.com/SandyYuR/fcitx5-android/commit/82090abfb71b3d74ce7c28d294b8d86142bc5fc9)<br>2026-09-06 | chore(构建): rime 源码与 prebuilt 改用 SandyYuR 自己的 fork，构建不再依赖 fxliang 账号 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/settings-appearance) |
| [8abbf722e](https://github.com/SandyYuR/fcitx5-android/commit/8abbf722ed3016ec0d2b4f3670f89a7ae9aef4d2)<br>2026-09-29 | feat(设置): 首页重构为两层分组，并加入搜索与跳转定位 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/settings-appearance) |
| [579b16321](https://github.com/SandyYuR/fcitx5-android/commit/579b1632116b7523ed969dc8b37029e9bed73bd2)<br>2026-10-21 | fix(ui): 提取并统一 MD3 页面、对话框与设置控件 | 历史提交与现行主题入口对照；是否仍生效以当前源码及功能说明为准。 [阅读文档](/features/theme/settings-appearance) |
