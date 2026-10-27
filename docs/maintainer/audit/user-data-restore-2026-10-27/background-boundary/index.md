# 后台清理边界与 CI 夹具竞态 · 2026-10-27

运行代码 [9b1f958c](https://github.com/SandyYuR/fcitx5-android/commit/9b1f958c604b91b2738da3d370636aadc8a34b1d)。补充发现，不重复计入旧139行。

209cca0e 的本地完整1218项通过，但[CI40409025789](https://github.com/SandyYuR/fcitx5-android/actions/runs/40409025789)中一项失败。下载并核对产物后，[完整失败栈](./ci-before-failure.txt)确认测试在第111行删除临时卷目录时，后台清理正在遍历/删除其中的目录；不是偏好值校验失败。该轮签名ARM64构建成功，单测失败，Nightly未发布。[失败元数据](./ci-before.json)

测试现在等待清理任务完成后再销毁夹具，所有相关用例的 teardown 也等待任务结束。同时收紧产品清理边界：后台只删除已标记恢复完成的目录残留；冲突解除或存储卷返回后，尚未恢复的 live root 交给启动、恢复页或方案访问路径在锁内处理。未从这次测试栈推断出用户数据丢失。

新增两项确定性检查覆盖“冲突目录已移除”和“可选卷返回”，后台不得主动恢复待处理 live root，同时仍能清理其他已恢复目录。[定向41项](./targeted-summary.json)、[完整1220项应用JVM及6项构建逻辑](./jvm-summary.json)均通过，零失败/错误/跳过；[构建日志](./full-tests.txt)。

[新CI40411170854](https://github.com/SandyYuR/fcitx5-android/actions/runs/40411170854)单测1220项与签名ARM64构建均已通过，[ARM64产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/40411170854/artifacts/12692104753)。[更新后的协议](./restore-design.md)

[CI安装包校验](./apk-verification.json)：仅ARM64直连Rime及C++库、2个dex、16KiB对齐。来源为该CI实际校验输出，未把旧本地APK当作此包。
