# Windows 本地引擎开发

本工作区提供纯 Windows 开发链路：当前 librime 工作树 → Android 静态库 → 已校验的开发 SDK → 主应用 Debug APK。不需要恢复 prebuilder/prebuilt，也不需要 WSL。正式版本的发布与回退继续使用[引擎更新流程](engine-update.md)。

## 工具与入口

工具统一位于 `D:\GitHub\tools`。工作区根的 `tools-env.ps1` 配置当前 PowerShell 进程，一键构建会自动调用；手动运行命令前先执行：

```powershell
. D:\GitHub\fx2-rime\tools-env.ps1
Get-Command git, python, node, adb, cmake, ninja
```

| 工具 | D 盘目录 |
| --- | --- |
| JDK 17 | `jdk-17` |
| Android SDK / NDK | `android-sdk` / `android-sdk\ndk\28.0.13004108` |
| CMake / Ninja | `android-sdk\cmake\3.31.6\bin` |
| MSVC 宿主编译器 | `msvc\14.51.36231\bin\Hostx64\x64` |
| Windows SDK | `windows-sdk\10.0.26100.0` |
| Python / Node / Git | `python-3.15` / `node-v26.12.1` / `git-2.55.0.windows.1` |

MSVC 和 Windows SDK 的所需文件从本机已有安装复制到 D 盘。Android 库由锁定 NDK 的 Clang 交叉编译；MSVC 仅生成宿主 OpenCC 工具。宿主编译器、资源工具、INCLUDE/LIB 路径均由脚本指向 D 盘。

## 修改源码并构建

在 `librime-src` 修改源码后，在工作区根运行：

```powershell
# 默认 ARM64：构建引擎和开发 SDK
pwsh -File .\build-engine-local.ps1

# 接着生成使用本地引擎的 Debug APK，并执行 APK/链接门禁
pwsh -File .\build-engine-local.ps1 -BuildApp

# 选择 ABI 或并行度
pwsh -File .\build-engine-local.ps1 -Abi x86_64 -Jobs 4
& .\build-engine-local.ps1 -Abi @('arm64-v8a', 'x86_64') -BuildApp
```

这些命令不会安装 APK 或发布 SDK。Debug 仍带 `.debug` 包名后缀，使用独立数据目录；安装和设备回归另按授权执行。

脚本用独占文件锁保护共享构建目录。多 ABI 依次构建，复用依赖和宿主数据工具；导出时要求所有 ABI 来自同一源树。

## 快照与增量构建

`--development` 用临时 Git 索引生成源树 SHA，纳入实际修改、删除和未被忽略的新文件。真实索引、HEAD、分支均保持原状，不会自动提交。依赖 gitlink 保留记录值；Android 实际依赖仍由 `sources.lock.json` 决定。

编译使用该快照。再次导出按内容同步，未改变的 C++ 文件保留时间戳，避免整库重编译。没有完成标记的中断源码缓存会重新解压。

Windows 解包和插件准备安全复制内部链接内容，不要求开启开发者模式或取得符号链接权限；越界、循环和无法解析的链接仍被拒绝。锁文件和补丁按 Git 的 LF 字节校验，执行补丁时禁用自动换行；其他内容差异仍按摘要错误处理。

## 输出与 Debug 接入

| 内容 | 路径 |
| --- | --- |
| 固定源码缓存 | `D:\GitHub\tools\rime-android-sources\<sha256>` |
| 源码快照、CMake 缓存及产物 | `D:\GitHub\tools\rime-build\windows` |
| 已校验的开发 SDK | `D:\GitHub\tools\rime-sdk-local` |
| 应用生成的 SDK 输入 | `fx-rime-only\native\rime-sdk` |

开发 SDK 的 `sdk-manifest.json` 记录 `development: true`、源树 SHA、基准提交、ABI 和逐文件摘要。导出前检查 ELF 架构、必需 API、依赖、OpenCC 数据和最终链接。

已有开发 SDK 时，也可单独构建应用：

```powershell
& .\build-debug.ps1 -GradleArgs @('-PlocalRimeSdkDir=D:/GitHub/tools/rime-sdk-local')
```

所选 `BUILD_ABI` / `-PbuildABI` 必须包含在开发 SDK 中。Gradle 校验并复制声明文件；缺少 ABI、来源标记、摘要不符或链接越界都会失败。带 `localRimeSdkDir` 的任何 Release 任务在执行前被拒绝。

下次普通构建不传此参数，Gradle 会按 `native/rime-sdk.lock.json` 恢复固定的发行 SDK。开发 SDK 不覆盖正式版本锁和下载缓存。

## 发行与验证边界

不带 `--development` 的配方仍要求源码已提交。正式 SDK 打包要求四 ABI，并拒绝开发输入；不能将开发目录改名作为 Release。

```powershell
. .\tools-env.ps1
python -m unittest discover -s librime-src/downstream/android -p 'test_*.py' -v
& .\build-debug.ps1 -Task ':convention:test' -GradleArgs @('-p', 'build-logic', '--tests', 'RimeSdkArchiveTest', '--no-daemon')
```

设备输入、方案兼容、词库持久化和性能需按修改另行验证。单 ABI 本机构建不能代替四 ABI 发行检查；本次结果见[工作区复核](changes/2026-11-05-local-review.md)。

## 下载问题

缓存只接受版本锁指定的 SHA-256。GitHub 下载慢时可按用户选择，通过代理下载同一文件、核对摘要后放入源码缓存，不改变依赖版本或摘要。本机已用用户指定的代理取得并校验 Boost；该代理的 HEAD 请求失败并不代表 GET 下载不可用。
