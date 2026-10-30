# 首次候选点选：Fcitx 对照、直连修正与耗时边界

补测确认：含 Fcitx 的默认版也存在首次点选比后续慢的现象。直连版此次修掉了一处
主线程读取缓存时等待后台调度的阻塞；现有小样本尚未证明首次整体上屏有稳定改善，
不能宣称已经消除用户真机的首次卡顿。

此前[响应性报告](/maintainer/history/DIRECT_RIME_RESPONSIVENESS_2026-10-18)的约 139ms 首次点选是
包含注入、按住、轮询的模拟器观察值，不能全部归因于输入法，也不能直接称为真机可感知延迟。
以前含 Fcitx 的万象录屏对照测的是预热连续输入及空格选词，没有测首次手指点选。

## 对照产品与方法

| 产品 | 实际 APK 源码 |
| --- | --- |
| 含 Fcitx 的默认分支历史版 | `cecea4940bb5a5db67f91d6b20e00ee5c97877ea` |
| 本轮修改前直连版 | `e6d317182e25d225a6128420542ea47d161c03b9` |
| 缓存读取修正版 | `f184f1e76b877607c5fd72d5d0895a170a71a639`，产品修改在 `e7172648` |

三方分别独立构建，只覆盖完全相同的 androidTest，不改参考产品源码；使用各版本的固定
gitlink，并核验实际嵌入提交、APK 哈希、测试源码哈希、运行时 native 身份。没有用 PR merge
代替工作分支源码。后续仅改驱动时，可复用产品目录完全一致的 APK，仍保留其真实提交号。

使用用户确认的万象 v18.1.3 base、LTS 模型及上传的两份 YAML；全拼、上下文调频、简码、
小字集必须在实际编辑会话中开启，模型必须实际 mmap。`user.yaml` 未保存上下文开关，
fixture 仅补入用户已确认的 `var/option/context_reorder: true`，其余内容不作性能改写。

- base ZIP SHA-256：`fc6495ba8489a898737ca29883dedaf61ff3baf593c78900dc59102c919d0e63`。
- LTS SHA-256：`1849e5e4acbdbfaf41fceeb86b739d5d0c85d574bd8a688562cba1e6beb78488`；
  asset `650124920`，更新时间 `2026-10-17T09:59:19Z`。
- fixture SHA-256：`1193ad6abe18732a677c45da9b7f9a075606b7b98172a48e8bfebb1b5ee9d742`。
- 完全相同的测试源码 SHA-256：`48bed3d958ecc8175dd576096d40d01aa3b4aa65909a90846617eb9a57cfce85`。

Android 35 / AOSP ATD / x86_64 同一可丢弃模拟器。每方部署后停止进程，做三次新进程测试；
每次真实触摸 `n`、`i`、候选“你”六轮，合计每方 18 次、三方 54 次准确上屏，没有预热词、
没有重复提交。首轮与同进程后五次分别统计。新进程仍使用已部署数据和系统页缓存，
不是重启手机后的存储冷缓存；三方按固定次序运行，不作稳定分位数或真机流畅度保证。

IME 的 Window.Callback 代理记录实际 ACTION_UP 派发时刻，不替换点击/长按/滑动监听。
独立编辑器进程分别记录 `InputConnection.commitText` **入口**、TextWatcher 和 onDraw；
两进程使用 `SystemClock.elapsedRealtimeNanos()`。查找视图、20ms 按住和轮询不会混入
以 ACTION_UP 派发为起点的数字；注入至派发等待另存。onDraw 是 CPU 绘制回调，不能当作
GPU、合成器或真机屏幕已呈现的证明。

## 缓存修正首轮对照

[工作流 39102152140](https://github.com/SandyYuR/fcitx5-android/actions/runs/39102152140)
通过。每方首次 3 个样本、后续 15 个样本，下面为中位数，单位 ms。

| 从 IME 抬手派发到 | 原 Fcitx | 修改前直连 | 缓存修正版直连 |
| --- | ---: | ---: | ---: |
| 首次：编辑器 InputConnection 入口 | 16.4 | 17.0 | 15.2 |
| 后续：编辑器 InputConnection 入口 | 5.1 | 5.4 | 4.9 |
| 首次：编辑器文字变化 | 33.9 | 21.0 | 34.3 |
| 后续：编辑器文字变化 | 6.8 | 6.2 | 5.9 |
| 首次：编辑器 onDraw | 45.7 | 30.3 | 45.5 |
| 后续：编辑器 onDraw | 12.6 | 12.8 | 11.1 |

原 Fcitx 三次首次文字变化为 33.9 / 26.1 / 67.1ms，均慢于其后续中位数。
直连缓存修正的“到编辑器入口”观察值小幅下降；首次文字变化和绘制没有稳定改善。
三个首次样本不足以认定小幅差值为可靠提速，更不能只选最好的轮次宣传。

首字母 `n` 到后端预编辑收集的首次中位数分别为 82.0 / 68.4 / 76.9ms，
后续为 15.9 / 14.4 / 13.6ms。它是另一项首次查询成本，不能与首次点选混为一个原因。

[完整逐次数据、原始日志和校验摘要](https://github.com/SandyYuR/fcitx5-android/tree/df591f782bc07248b1917fe6544a711e917ec022/docs/maintainer/perf/2026-10-18-candidate-tap/initial-cache-f184f1e7)。

## 系统 trace 定位

[独立诊断工作流 39102646488](https://github.com/SandyYuR/fcitx5-android/actions/runs/39102646488)
只用同一批 APK，每方一轮并抓 Perfetto。这些带 trace 的耗时单列，不与上表混算。
[原始 trace 产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/39102646488/artifacts/12127171867)
有保留期限；[逐事件记录、SQL 导出结果与原始文件哈希](https://github.com/SandyYuR/fcitx5-android/tree/df591f782bc07248b1917fe6544a711e917ec022/docs/maintainer/perf/2026-10-18-candidate-tap/trace-f184f1e7)
随分支保存。

缓存修正版该轮的首次点选，相对 IME 抬手派发：

| 阶段 | 起止/耗时 |
| --- | --- |
| librime 选词 | +2.1ms 开始，7.1ms 完成，含快照 |
| 服务处理 commit | +10.3ms 开始，约 0.83ms |
| 目标编辑器 InputConnection 入口 | +11.0ms |
| 编辑器首次 DynamicLayout 排版 | +11.2ms 开始，约 18.0ms |
| TextWatcher 记录文字变化 | +33.6ms |
| 编辑器 onDraw 记录 | +51.5ms |

该轮提交已经及时进入编辑器，之后的首次中文排版在目标应用进程中执行。
把全部耗时归到直连后端、再重写候选提交队列，没有这份 trace 的支持。
模拟器 RenderThread 后面还出现长 GPU 同步/首次 shader 工作，这进一步说明 CPU onDraw
与真实显示不是同一个终点。此处定位仅适用于该轮模拟器，不能直接断言用户手机同因。

首个 `n` 的原生查询约 66.9ms，其中线程状态 Running 20.0ms、Runnable 4.3ms、
不可中断内核等待 D 42.6ms。未采集到 `blocked_function`、`io_wait` 归因与具体文件的
缺页计数，因此尚不能把等待指定为 LTS 模型、某个词典或锁。对整份模型作大范围预读
需要额外的内存/I/O 评估，本轮没有把这个嫌疑改成自动预读策略。

trace 有一项配置提示：旧采集配置缺少 `flush_period_ms`，影响采集器内存使用；
后续工具已补为 1000ms。保留的原始诊断记录没有替换为修正后的数据。

## 应用实际修改

1. `FcitxConnection.runImmediately` 原来把含 `Dispatchers.Default` 的生命周期上下文传给
   `runBlocking`。主线程读 volatile 缓存也要调度 worker 并同步等待；原 Fcitx 版同样存在。
   现在去掉即时读取的 dispatcher，保留生命周期 Job/取消关系，缓存直接在调用线程读取。
   显式 native 调用仍通过 owner dispatcher 到 `rime-main`，不把 librime 运算放到主线程。
2. 新增三项并发契约测试：后台 worker 被占用时缓存读仍能返回、生命周期停止仍取消、
   显式 native owner 仍生效。所有原输入顺序、代次过滤和提交消费逻辑保留。
3. 补齐每代次前两次候选/提交的真机阶段计时；新增计时不含输入内容。横向与展开候选
   仍走相同的按键顺序队列，只在同一路径外围计时。

新日志与已有日志结合阅读：

| `RimeInputTiming` 项 | 说明 |
| --- | --- |
| `select_queue service_wait_us` | 候选点击登记至服务队列开始执行 |
| `select owner_wait_us/native_publish_us` | 已有：owner 等待、原生选词与快照发布 |
| `commit event_wait_us` | commit 生成至服务主线程开始消费，含发布尾部及事件调度 |
| `commit service_commit_us` | 服务提交函数及编辑器调用返回前的耗时；跨进程编辑器后续排版通常在返回后 |

选词 ordinal 和所有提交 ordinal 各自计数；有 ASCII/自动提交时不能仅按相等 ordinal 配对，
应结合编辑器代次与相邻时间。后续提交不会持续打印这些新增计时。
同进程编辑器或同步 InputConnection 实现也可能在调用内排版，不能把 `service_commit_us`
全部认作后端计算；需结合目标应用/trace 判断调用边界。

## 最终版本与验证

最终应用源码 `9d5c7992a772b9965f23404ca01a1a891769c3a0` 增加上述计时；
[末轮三方对照 39103477376](https://github.com/SandyYuR/fcitx5-android/actions/runs/39103477376)
通过，仍是每方三次新进程、每次六次点选，共 54 次准确上屏。单位 ms，中位数：

| 从 IME 抬手派发到 | 原 Fcitx | 修改前直连 | 最终直连 9d5c7992 |
| --- | ---: | ---: | ---: |
| 首次：编辑器 InputConnection 入口 | 12.6 | 16.8 | 18.6 |
| 后续：编辑器 InputConnection 入口 | 7.6 | 5.2 | 6.5 |
| 首次：编辑器文字变化 | 19.0 | 37.1 | 31.6 |
| 后续：编辑器文字变化 | 8.6 | 11.0 | 9.0 |
| 首次：编辑器 onDraw | 37.4 | 48.0 | 41.1 |
| 后续：编辑器 onDraw | 20.4 | 23.0 | 21.2 |

最终直连的三次首次文字变化为 40.8 / 31.6 / 26.4ms，仍有首次成本。
本轮到编辑器入口的数字比修改前高，文字变化/绘制数字低；上一轮入口数字则小幅下降。
两轮共同说明小样本波动明显，不能挑一个终点宣称稳定提速，更不能把不同主机轮次当成单项
代码修改的因果收益。[最终逐次原始数据与日志](https://github.com/SandyYuR/fcitx5-android/tree/df591f782bc07248b1917fe6544a711e917ec022/docs/maintainer/perf/2026-10-18-candidate-tap/final-9d5c7992)
完整保留。

新增计时在实际万象触摸测试中工作：最终版首轮样例的服务队列等待约 0.64ms、owner 等待
0.63ms、native + publish 14.8ms、commit 事件等待 0.56ms、服务提交调用约 3.1ms。
这些阶段有部分重叠，且“服务提交返回”与“目标编辑器入口”并非相同终点，不能直接逐项求和。

- [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39103480207) 全通过：
  480 项 JVM、四 ABI 构建及依赖/DEX/页对齐检查。
- [Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39103480258) 全通过：
  原生 sanitizer、引擎 smoke、head ARM64 与 10 项完整前端设备回归。
- ARM64 安装包：[分支 head 产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/39103480258/artifacts/12127657581)，
  本会话文件名 `zhongzhougoose-candidate-9d5c7992-arm64-v8a-debug.apk`，23,173,008 bytes。
  包内完整构建哈希已核验，仅两个直连原生库，22 个 DEX 核验通过，ARM64 16KiB 对齐。
- APK SHA-256：`06d2dbad9289ad1470e72073561add8876dcc7ee32e21963ce6ef93e87cd5b45`。
- 调试证书 SHA-256：`cbe348c7e66fa93c1078ee59ea34660284e9da64a58c021e92aeeb16b9d7302b`。
  本次 CI 调试签名与上一份 CI 包不同；若系统拒绝覆盖升级，先按用户手册导出方案与用户库备份，
  不要直接卸载带数据的旧版。正式连续发行应使用固定签名。
- [机器可读验证记录](https://github.com/SandyYuR/fcitx5-android/blob/d50e2272de795e9110cc497a0f37017551f54747/docs/maintainer/direct-rime-candidate-validation-2026-10-18.json)。

测试驱动曾因停止输入法进程触发 Android 回退系统键盘而超时；
失败的工作流 [39090546018](https://github.com/SandyYuR/fcitx5-android/actions/runs/39090546018)
没有有效点选结果，未计入任何性能数字。驱动已改成新测试进程开始后选择并核验目标 IME。

原始模型与配置不因本轮优化而改变。仍未取得用户真机上“首次点选卡顿已消除”的证据。
