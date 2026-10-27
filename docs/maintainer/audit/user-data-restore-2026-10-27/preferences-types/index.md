# 恢复前偏好值校验 · 2026-10-27

运行代码 [209cca0e](https://github.com/SandyYuR/fcitx5-android/commit/209cca0e7c8e0954d00a6c939e07d05cba160a8f)。补充发现，不重复计入旧139行。

仅检查 XML 完整不能保证 Android 能读取其偏好值。恢复前现检查标准值类型、数字范围、布尔值及集合成员，拒绝未知类型、错误嵌套和非字符串位置的正文，避免导入后重启才发现偏好损坏。大小限制为最大堆的1/16，最低1 MiB、最高16 MiB；流式检查不构造完整偏好 Map。

[API23/28 的9种测试方法](./TEST-org.fcitx.fcitx5.android.data.SharedPreferencesRestoreTest.xml)共18次执行，其中本批新增8次。合法样例还通过 Android SharedPreferences 实际读取，涵盖数字、NaN、布尔、转义字符串及含 null 的集合。[完整1218项应用JVM与6项构建逻辑](./jvm-summary.json)均零失败/错误/跳过；[构建日志](./full-tests.txt)。

[本批ARM64 CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/40409025789)签名构建通过，单测中另一恢复用例遇到夹具删除与后台清理竞态；[后续修复与原始失败](../background-boundary/index.md)。先前缓存路径批次8dca031c的[CI40377515000](https://github.com/SandyYuR/fcitx5-android/actions/runs/40377515000)已全绿，完整1210项；该批[ARM64产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/40377515000/artifacts/12678382320)不含本批XML续修。

设备关机验证运行在8dca031c，详见[实际词典持久化记录](../../native-shutdown-2026-10-27/index.md)。209cca0e的Android恢复交接与偏好/WAL读回[已通过](../android-handoff/index.md)。
