# 万象立即输入与候选点选：原始结果

测试是同一天两轮开发验证，固定修改前直连产品 `2f6a0ea0`，
不替代[此前含 Fcitx 默认分支的画面对照](../2026-10-17-wanxiang-screen/README.md)。
实现、结果解读与限制见[响应性报告](../../DIRECT_RIME_RESPONSIVENESS_2026-10-18.md)。

`initial-e65cee02/` 是第一轮：会话复用、首键接收及候选队列顺序，
运行 [39087275365](https://github.com/SandyYuR/fcitx5-android/actions/runs/39087275365)。
其首次选词没有改善，原数据完整保留；随后消除主线程推进顺序队列的依赖。

`final-e6d31718/` 是包含后台消费修正的最终版本，运行
[39088211736](https://github.com/SandyYuR/fcitx5-android/actions/runs/39088211736)。
两版均通过，原始输入/点选结果、模型证明和六轮数据全部保留。
六轮编码观察值中位数 163.7 → 113.5ms、两键到候选 221.2 → 164.5ms；
点选 67.5 → 66.1ms，首次仍慢，不能据此宣称点选或所有冷启动已改善。

每组文件包括：

| 文件 | 内容 |
| --- | --- |
| `before.json` / `after.json` / `comparison.json` | APK 内实际构建哈希、模型 mmap、每轮观察耗时及成功断言 |
| `summary.json` | 前两轮、后四轮、六轮中位数与新版本各轮 native 创建/复用计数 |
| `*-instrumentation.txt` | Android 测试运行器结果 |
| `*-logcat.txt.gz` | 完整原始 logcat；解压 SHA-256 在 summary 中 |
| `*-commit.txt` / `apk-sha256.txt` / `harness-sha256.txt` | 独立产品提交、APK 与相同测试源码指纹 |
| `rebind-fixture.manifest.json` | 官方方案、LTS 与原始 YAML 哈希；上下文开关补值说明 |
| `validation.json`（若有） | 该阶段其他构建/回归、分支包与 PR 临时合并包的来源区分 |

每个版本六轮：第一次显示键盘与后续五次 `restartInput`，立即输入 n、i、点击候选“你”，
每次必须只提交一次，最终编辑器为六个“你”。前后两版均使用新建 profile、相同
v18.1.3 base + LTS 和用户 YAML，显式开启用户确认的上下文调频；模型必须实际映射。

fixture 准备已初始化并部署引擎，不是进程冷启动。计时包含 UI 注入、20ms 按住与轮询，
候选指标还包含 n、i 两次触摸；它们是观察值，不是逐键 native 耗时或实际屏幕呈现时间。
固定先 before 后 after、单台 Android 35 x86_64 ATD 模拟器、各六轮，不能据此推导稳定
P95/P99、所有真机收益或单项改动的独立因果效果。
