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

**Gradle daemon 不按环境变量匹配复用**，所以客户端进程内清掉变量救不回旧 daemon。处理办法是进脚本时先检测该变量，非空就 `gradlew --stop` 丢弃被污染的 daemon 再重建；`-NoDaemonReset` 可跳过，代价是每次启动都重启 daemon。脚本同时设 `ANDROID_USER_HOME` 指向工具链内的 `android-home`，复用一个既有 debug keystore，不改系统环境、也不改签名身份。

### Kotlin daemon 与 in-process 编译

本机 Kotlin 编译守护进程可能坏在临时文件上，报 `java.nio.file.AccessDeniedException: ...\kotlin-daemon-client-*.tmp`。绕法是让 Kotlin 在 Gradle 进程内编译：

```powershell
cmd.exe /c "gradlew.bat -Pkotlin.compiler.execution.strategy=in-process -PbuildABI=arm64-v8a --console=plain :app:assembleFxDebug"
```

在 PowerShell 里必须套一层 `cmd.exe /c`，否则 `-P` 参数会被 PowerShell 拆坏。

## 子模块：浅克隆与取不到 pin

`native/prebuilt` 是浅克隆，`git submodule update --init` 常报 `Unable to find current revision in submodule path` 或留下空工作树。补救是按 SHA 单独取：

```sh
git -C native/prebuilt fetch --depth 1 origin <pin>
git -C native/prebuilt checkout --detach <pin>
```

被中断的克隆会留下 `native/prebuilt/.git` 与 `.git/modules/native/prebuilt`（含 `shallow.lock`），**两处都要先删掉再重试**。直连 GitHub 不稳时换镜像 `https://github.boki.moe/github.com/<owner>/<repo>.git`，注意 `insteadOf` 不作用于子模块克隆，要对 `submodule.<name>.url` 单独设置。

## 出包之后

两个脚本默认在出包后跑 CI 的两道 native 门禁（`verify-direct-rime-apk.py` 与 `audit-direct-rime-link.py`，对齐 PR CI / Direct Rime CI）。门禁失败不安装并退出非零。要跳过用 `-SkipVerify`，但跳过了就别声称包是合格的。

`verify-direct-rime-apk.py` 里的 `allowed` 是**打包原生库白名单**：新增任何带 `.so` 的依赖，必须把库名登记进去，否则 Debug 出包一切正常、只有 Release 门禁（和 CI）会在 `packaged == allowed` 断言上变红。2026-10-28 接入 JS 语音插件时 `libquickjs.so` 就是这么漏掉的。

验证 APK 体积必须 `:app:clean` 后再打包——增量构建的体积会虚高。

## adb

OnePlus 设备掉线频繁，先 `adb devices` 确认。若 `adb` 起不来并报 `cannot open ...\Temp\adb.log: Permission denied`，是临时目录权限问题：在命令内把 `TEMP`/`TMP` 指到可写目录，再 `adb kill-server` / `adb start-server`。

Debug 与 Release 包名不同（debug 带 `.debug` 后缀），可以共存；debug 的数据目录独立，装机后要重新导入方案并部署。

## PowerShell 与长命令

- 管道陷阱：`| Select-Object -First N` 会提前关管道、把 gradle 杀掉，而旧 XML 还在。正解是重定向到文件，看 `$LASTEXITCODE` 与 XML 的 `LastWriteTime`。
- 长构建（打包、全量单测）放后台任务，别塞进有硬性超时的同步调用里——超时会直接杀掉前台构建，看起来像是构建失败。

## 现取而不是照抄

工具链版本、ABI、构建开关的权威在 `build-logic/convention/src/main/kotlin/Versions.kt`；当前分支、HEAD、worktree 与子模块状态用 git 现取：

```sh
git -C fx-rime-only status --short --branch
git -C fx-rime-only worktree list
git -C fx-rime-only submodule status
```
