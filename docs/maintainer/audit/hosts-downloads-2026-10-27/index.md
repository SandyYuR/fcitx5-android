# hosts 下载生命周期与断点恢复 · 2026-10-27

代码：[d3cb8769](https://github.com/SandyYuR/fcitx5-android/commit/d3cb87692b638a36dde048435658de1afcc8738e)。对应旧报告 P2-3。

## 本批改动

Android 10 及以上的直连 hosts 下载交给 WorkManager 2.11.2；下载页退出只解除观察，重新进入按资产身份恢复进度。Android 6/9 与普通系统下载继续用 DownloadManager，项目最低版本仍为 API 23。任务入队与取消按调用顺序在后台执行。

暂存文件和检查点放在私有 noBackup 目录，使用强 ETag、Range 与 If-Range 续传；服务器忽略 Range、改变 ETag、返回错位 206 或 416 时安全重新下载。暂存及发布复制均检查空间，单文件限额 512 MiB，预留 32 MiB。取消后等待网络回调结束再释放文件锁，避免清理后仍有迟到写入。

完整下载校验后才发布到 MediaStore。pending URI 与实际内容摘要用于恢复发布中断，只清理本任务拥有的临时文件和 pending 行；保留已经发布的文件及其他应用的记录。通知支持查看进度与取消。

## 验证与边界

- [31 项定向执行](./targeted-tests.txt)：HTTP 断点、校验、MediaStore 发布窗口、真实 Worker、真实 WorkManager 记录、页面重新观察、取消顺序、后台调度以及 API 23/28 启动路径。
- [完整回归](./jvm-summary.json)：1156 项应用 JVM、6 项构建逻辑，失败/错误/跳过均为 0；[构建日志](./full-tests.txt)。
- [远端 CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/40337615943) 全绿；[ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/40337615943/artifacts/12657643573) 已产出，下载 ZIP 摘要与 GitHub 元数据一致。
- [APK 检查](./apk-verification.json)：只有 arm64-v8a 的 librime 直连和 C++ 运行库，无 Fcitx 原生依赖，满足 16 KiB 对齐。

本批框架测试模拟组件重新创建和持久记录恢复，尚未用设备杀进程验证这条下载路径。WorkManager 任务受系统调度；用户强行停止应用后须重新打开才能继续，入队数据库提交前的退出也不保证已登记任务。上述验证不等同于物理断电保证。

[设计与兼容说明](./download-design.md) · [来源记录](./provenance.json)
