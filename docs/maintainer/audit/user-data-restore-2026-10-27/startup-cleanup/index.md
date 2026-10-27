# 恢复残留清理与启动等待 · 2026-10-27

代码：[544f84a8](https://github.com/SandyYuR/fcitx5-android/commit/544f84a852aed9f008e68acad184e71fac5447a8)。补充发现，不重复计入旧139条。

启动时先把目录恢复到一致状态，递归删除旧目录/暂存目录交给 IO。目录已经可用时，输入引擎复用该主进程的启动判定，避免排在大量清理工作后面。其有效性依赖恢复进程先终止旧主进程、再替换目录这一约束；可选待恢复路径仍须加锁复查。

如果另一恢复进程正在持锁，Application 启动使用 tryLock，立即转到恢复进度页，不在主线程等待分钟级恢复任务。所有权标记和日志在后台清理成功前保留，可再次中断后重试。

[完整1204项应用测试与6项构建逻辑](./jvm-summary.json)，零失败/错误/跳过，新增7项执行。新增检查包括分离暂存/已回滚/已提交清理，活动锁阻止启动，以及已就绪路径不等待清理锁；[构建日志](./full-tests.txt)。

[CI40371186736](https://github.com/SandyYuR/fcitx5-android/actions/runs/40371186736)已全绿，[签名ARM64产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/40371186736/artifacts/12672104539)包含这一批优化。Android完整恢复页面交接及真实卷拔插仍待补验。

[更新后的设计](./restore-design.md)
