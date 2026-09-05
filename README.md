# fcitx5-android

[Fcitx5](https://github.com/fcitx/fcitx5) input method framework and engines ported to Android.

*** 停止更新了，留意相关fork可能有更优秀的修复隐藏bug或性能改善 ***

本mod是基于原仓几乎全程 vibe coding 而成，在原仓上增加了相当若干特性，包名为了方便网友同时使用体验使用改成了`org.fcitx.fcitx5.android.fx`，总体上可以理解成原仓做的超集, 可在[releases](https://github.com/fxliang/fcitx5-android/releases)下面下载体验。


微信赞赏码
<img width="600" height="600" src="https://github.com/user-attachments/assets/503063b1-4951-4d88-aaa3-463585135233" />

默认通过 `./gradlew :app:assembleDebug` 等常规命令构建的是带 `.fx` 后缀的 fx 变体（包名 `org.fcitx.fcitx5.android.fx`、界面标题携带 `.fx`、APK 文件名体现 `.fx`、主要输出路径为 `app/build/outputs/apk/fx/<buildType>`，数据目录为 `/Android/data/org.fcitx.fcitx5.android.fx/...`）。同时为了兼容常见脚本，构建后会自动同步一份 APK 到 `app/build/outputs/apk/<buildType>`（不含 `fx/` 这一层）。想要生成与主仓命名一致的 mainline 变体时，可加上 `-PincludeMainlineFlavor=true` 并使用对应的 variant，例如：

```
./gradlew -PincludeMainlineFlavor=true :app:assembleMainlineDebug
./gradlew -PincludeMainlineFlavor=true :app:assembleMainlineRelease
```

mainline 变体会输出无 `.fx` 后缀的包名、应用名、资源以及 APK/日志命名（输出路径为 `app/build/outputs/apk/mainline/<buildType>`，数据目录 `/Android/data/org.fcitx.fcitx5.android/...` 亦跟主仓一致），运行时与 upstream 原仓保持一致；其余构建逻辑与 fx 变体完全相同。



