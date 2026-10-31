# Windows 本机构建环境

这页记录在 Windows 上构建、测试与出包的**本机侧**经验，供维护者排查环境问题。
CI 用的是 GitHub 托管的 Ubuntu，与这里无关；下面的路径是作者机器上的布置，换机时按同名变量自行替换即可。

## 工具链与一键脚本

工具链集中在一个目录（作者机器上是 `D:\GitHub\tools`）：JDK 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`、build-tools `36.1.0`，以及 BuildConfig 用的 ECM/gettext 和独立的 `gradle-home`、`tmp`。

工作区根（不在任何 git 仓库内）的一键脚本会把 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`/`TMP`/`ECM_DIR`/`GETTEXTDATADIR` 都设进进程，**不要另设系统级环境**：

```powershell
pwsh -File build-debug.ps1              # debug APK(默认 arm64-v8a) + 出包后门禁
pwsh -File build-debug.ps1 -Test        # 只跑 JVM 单测
pwsh -File build-debug.ps1 -Install     # 构建后 adb install -r
pwsh -File build-debug.ps1 -SkipVerify  # 跳过 verify/audit 门禁
pwsh -File build-debug.ps1 -Abi x86_64  # 其它 ABI(需先放开 prebuilt 稀疏检出)
pwsh -File build-release.ps1 [-Install] [-VerifyOnly]   # 本地签名 Release
```

## 三个本机坑

### `python3` 是占位 stub

Windows 的 `python3` 会落到 WindowsApps 的占位程序：`python3 --version` 静默退出、无输出（exit 49）。所以 `tools/*.py` 那几道 CI 门禁在本机必须用真实解释器跑，脚本里的 `Resolve-RealPython` 会排除 `*\WindowsApps\*` 后自选，找不到就告警并跳过门禁。手动跑门禁时请显式写解释器路径。

### `ANDROID_SDK_HOME` 与 Gradle daemon

宿主环境里遗留的 `ANDROID_SDK_HOME=C:\Android` 不可写，会同时打坏两处：

- Debug 出包停在 `:app:validateSigningFxDebug`，报 `java.nio.file.AccessDeniedException: C:\Android\.android\debug.keystore.lock`（AGP 把 keystore home 指了过去）。
- 已启动的 Gradle daemon 会把这个变量带进 AGP，`AndroidLocationsBuildService` 去写 `C:\Android\analytics.settings` 失败。

**Gradle daemon 不按环境变量匹配复用**，客户端清除环境变量不会更新已启动的 daemon。脚本只在检测到遗留 `ANDROID_SDK_HOME` 且未传 `-NoDaemonReset` 时执行 `gradlew --stop`；该开关会跳过清理，可能继续复用被污染的 daemon。
脚本将 `ANDROID_USER_HOME` 指向工具链内的 `android-home`，复用既有 debug keystore，不改系统环境或签名身份。

### Kotlin daemon 与 in-process 编译

本机 Kotlin 编译守护进程可能坏在临时文件上，报 `java.nio.file.AccessDeniedException: ...\kotlin-daemon-client-*.tmp`。绕法是让 Kotlin 在 Gradle 进程内编译：

```powershell
cmd.exe /c "gradlew.bat -Pkotlin.compiler.execution.strategy=in-process -PbuildABI=arm64-v8a --console=plain :app:assembleFxDebug"
```

上例是可选写法，不要求所有命令都经过 `cmd.exe /c`。在已配置环境的 PowerShell 中，也可用 `& .\gradlew.bat` 并把每个 `-P...` 参数单独引用；避免将多个参数合成一个字符串。

## 子模块：浅克隆与取不到 pin

`native/prebuilt` 是浅克隆，`git submodule update --init` 常报 `Unable to find current revision in submodule path` 或留下空工作树。补救是按 SHA 单独取：

```sh
git -C native/prebuilt fetch --depth 1 origin <pin>
git -C native/prebuilt checkout --detach <pin>
```

遇到 `.git` 指向失效或锁文件残留时，先检查工作树、gitdir、正在运行的 Git 进程和未提交修改。**不要无条件删除子模块 `.git` 或 `.git/modules/...`**；优先定位已验证 pin 并恢复现有 checkout，确需重建时先保存本地内容并核对要处理的路径。
镜像只在明确采用可信来源时配置，不把网络问题当作更换源码或 pin 的理由。Git 的 URL 改写是否生效取决于实际 URL 与生效配置；不能笼统断言 `insteadOf` 对子模块无效。

### Git/MSYS 权限错误

`git submodule status` 若在 `sh.exe` 报 `couldn't create signal pipe` / Win32 error 5，说明 shell 启动受限，不证明子模块 pin 损坏。可以先用原生 Git 子命令分别检查：

```powershell
git ls-tree HEAD native/prebuilt native/rime-prelude
git -C native/prebuilt rev-parse HEAD
git -C native/prebuilt status --short
git -C native/rime-prelude rev-parse HEAD
git -C native/rime-prelude status --short
```

修复真实写入或工具执行权限问题时使用宿主提供的审批机制，不修改安全设置或换通道绕过拒绝。

## 出包之后

两个脚本默认在出包后跑 CI 的两道 native 门禁（`verify-direct-rime-apk.py` 与 `audit-direct-rime-link.py`，对齐 PR CI / Direct Rime CI）。实际门禁执行失败时不安装并退出非零。当前脚本在缺少可用 Python 或没有找到 APK 等路径上可能跳过门禁，因此必须核对日志确实执行了两项检查；不能仅凭脚本退出 0 判断通过。`-SkipVerify` 只用于明确跳过验证的诊断场景，不能把跳过的产物标成已验收。

`verify-direct-rime-apk.py` 里的 `allowed` 是**打包原生库白名单**：新增任何带 `.so` 的依赖，必须把库名登记进去，否则 Debug 出包一切正常、只有 Release 门禁（和 CI）会在 `packaged == allowed` 断言上变红。2026-10-28 接入 JS 语音插件时 `libquickjs.so` 就是这么漏掉的。

验证 APK 体积必须 `:app:clean` 后再打包——增量构建的体积会虚高。

## adb

OnePlus 设备掉线频繁，先 `adb devices` 确认。若 `adb` 起不来并报 `cannot open ...\Temp\adb.log: Permission denied`，是临时目录权限问题：在命令内把 `TEMP`/`TMP` 指到可写目录，再 `adb kill-server` / `adb start-server`。

Debug 与 Release 包名不同（debug 带 `.debug` 后缀），可以共存；debug 的数据目录独立，装机后要重新导入方案并部署。

## PowerShell 与长命令

- 管道陷阱：`| Select-Object -First N` 会提前关管道、把 gradle 杀掉，而旧 XML 还在。正解是重定向到文件，看 `$LASTEXITCODE` 与 XML 的 `LastWriteTime`。
- 长构建（打包、全量单测）放后台任务，别塞进有硬性超时的同步调用里——超时会直接杀掉前台构建，看起来像是构建失败。

## 现取而不是照抄

Windows JVM 验证使用真正的原子文件替换；不要为了让测试变绿而先删除目标文件。数据库测试仍打开真实 SQLite 文件，只对宿主不支持的路径格式及过长的测试缓存路径使用测试上下文适配。

符号链接测试会先实际尝试创建链接。仅 Windows 明确返回缺少符号链接权限时，该用例被标记为宿主不可执行；这不是功能验收通过。Linux JVM 与设备 CI 的 `tools/check-junit-results.py` 要求用例实际执行且失败、错误、跳过均为零。手势验收使用 `tools/test-settings-navigation.sh`，保持系统动画开启，并分别检查完成返回、取消返回、连续返回、搜索态、未保存主题和主题页横滑。

系统边缘返回需要带 SystemUI 的完整系统镜像；当前 API 34/35/36 矩阵使用 `google_apis`，普通 Material 页面检查仍可使用 ATD。脚本先确认设备在线、SystemUI 存在并运行、手势导航模式已生效，再执行测试。XML、系统日志和实际屏幕截图随 CI 产物保存，不能用无 SystemUI 的 ATD 截图代替系统手势验收。

CI 先完成 APK 编译、停止构建守护进程，再启动完整镜像，避免首次开机与编译争用资源。测试前还须确认桌面已取得焦点；若首次启动未就绪，脚本保留截图、仅重启已解析的系统桌面一次，再次检查。测试开始后的应用无响应和手势失败仍直接判为失败。

工具链默认值与支持的 ABI 在 `build-logic/convention/src/main/kotlin/Versions.kt`；覆盖项、`BUILD_ABI` 优先级与列表解析分别查 `ProjectExtensions.kt`、`Utils.kt` 和 `BuildAbiSelection.kt`；当前分支、HEAD、worktree 与子模块状态用 git 现取：

```sh
git -C fx-rime-only status --short --branch
git -C fx-rime-only worktree list
git -C fx-rime-only submodule status
```


`build-debug.ps1 -Task` 接收单个 Gradle 任务，`-Test` 会覆盖为 JVM 单测任务；不能把任务与 `--tests` 等参数整段塞入 `-Task`。需要筛选测试时，在已配置环境的 shell 中向 Gradle 分别传递任务和参数。
