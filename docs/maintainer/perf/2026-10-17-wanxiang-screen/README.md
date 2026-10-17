# 万象触摸与录屏对照原始数据

方法与结论见[报告](../../DIRECT_RIME_SCREEN_PERF_2026-10-17.md)。正式运行
[39041913893](https://github.com/SandyYuR/fcitx5-android/actions/runs/39041913893)
在同一个 Android 35 x86_64 KVM 模拟器完成 14 轮、4,752 次触摸及 1,584 次硬件参考按键。
两版匹配相同输入长度的完整提交文本；这不是人工标注的汉字识别准确率。

| 文件 | 用途 |
| --- | --- |
| `comparison.json` | 各轮结果和三种场景的分组统计，采用每轮统计量的中位数／范围 |
| `runs.json` | 实际顺序、完整语料与提交文本、逐轮文本等价校验 |
| `verification.json` | 下载后独立核验源码、事件序列、状态、模型、输出与对时 |
| `sensitivity.json` | 原工具更高像素门槛的复算；不重新拟合时钟 |
| `provenance.json`、`apk-sha256.txt` | 四 APK 哈希、实际主包原生库列表、相同 instrumentation 及中立编辑器哈希 |
| `screen-wanxiang.manifest.json` | 官方 base、模型、原始 YAML 及组合夹具哈希 |
| `runtime-user.yaml` | 从原始 user.yaml 只补入上下文调频开启值的测试副本 |
| `archive-source.json` | 测试源码、产品版本、APK 生产运行及分析工具哈希 |
| `actions-artifacts.json` | 原始 artifact ID、大小、摘要及过期时间 |
| 每轮 `schedule.json.gz`、`result.json.gz` | 计划与实际按键、进程 CPU/PSS、源码和真实映射库／状态 |
| 每轮 `host-before.json`、`host-after.json` | 编辑区文本、区域坐标与独立对时色块的帧时钟 |
| 每轮 `effective-schema.yaml.gz` | 实际部署配置，原字节 gzip 保存 |
| 每触摸轮 `trace.tsv.gz`、`pts.txt.gz`、`marker.rgb.gz` | 完整逐帧差分、视频时间戳和对时色块 RGB |
| 每触摸轮 `analysis.json`、`ffprobe.json.gz` | 时钟残差、实际节奏、画面检测统计和视频元数据 |
| 每轮 `memory-*.txt`、`instrumentation.txt` | 设备内存快照与测试执行结果 |
| `steady-r1-*/*.png` | 首轮两版输入前后截图 |
| `SHA256SUMS` | 此目录永久文件的 SHA-256，清单自身除外 |

较大的原始 JSON 也以 `.json.gz` 保存，避免逐键事件撑大 PR 文本差异。
gzip 的 mtime 固定为 0，解压得到原文件字节；没有删减轨迹行。
视频约 55 MB、完整设备日志留在 Actions artifact，保存至 2026-10-31；APK 保存至 2026-10-20。
不将模型、APK 和 MP4 写入 Git；官方 LTS 标签可变，复现引擎输入必须保留报告指定哈希的模型。

从仓库根目录复核摘要及统计：

```sh
(cd docs/maintainer/perf/2026-10-17-wanxiang-screen && sha256sum -c SHA256SUMS)
python3 tools/screen-perf/summarize.py docs/maintainer/perf/2026-10-17-wanxiang-screen
python3 tools/screen-perf/sensitivity.py docs/maintainer/perf/2026-10-17-wanxiang-screen
```

汇总与敏感性脚本会覆盖相应 JSON，输出应与归档逐字节一致。
如需重新从视频提取像素，下载正式运行的视频并放到各触摸轮的 `run.mp4`；
非首轮的 `before.png` 截图需从原始 results artifact 获取。
`analyze.py` 还需 Java 17、FFmpeg 和 ffprobe，读原视频并按独立色块对时，不用候选结果拟合时钟。
