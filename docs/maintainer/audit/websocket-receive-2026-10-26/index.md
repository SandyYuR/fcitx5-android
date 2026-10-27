# WebSocket接收上限与CI回归 · 2026-10-26

源码 `c72115de`，运行修改 `c2ba6510`。[完整来源与产物校验](./provenance.json)。

原A9已补齐消息组装前的帧/分片累计上限。登录HTTP连接继续复用；WebSocket单独使用受限流，保留Cookie与调度器。禁用压缩，默认解码预算为`min(32MiB,最大堆/32)`。[协议兼容边界](./receive-limits.md)：WS端点须直接返回101，明文SOCKS拒绝，WSS和HTTP代理受保护。

[68项定向执行](./targeted-tests.txt)含24项新增；[完整1125项应用测试](./full-tests.txt)及6项构建逻辑通过，失败/错误/跳过均0。独立64MiB JVM关闭紧凑字符串：1GiB声明在正文读取0字节时拒绝；正常4MiB消息通过真实OkHttp回调，采样堆峰值23891968字节。它不是Android整机内存验收。

[红色CI40318378368](https://github.com/SandyYuR/fcitx5-android/actions/runs/40318378368)仅旧池复用断言失败，APK构建成功。后续测试验证HTTP池复用、WS池隔离、共享调度器，[CI40319452811全部成功](https://github.com/SandyYuR/fcitx5-android/actions/runs/40319452811)。失败记录保留，未删除断言绕过隔离验证。原型阶段ProxySelector内部抛异常曾导致重试不收敛，已改在应用拦截器入口拒绝并固定已验证路由快照；原始失败同样保留。

[下载本批ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/40319452811/artifacts/12648094518)。APK/报告归档SHA-256均已核对，远端HTML确认为1125项、0失败、0跳过；APK仅ARM64直连原生库、2dex、16KiB对齐。下载任务的后续未提交改动不在此包。

原139行还剩P1-8、P2-3两行，P1-10关机词典持久化待验证。D7仍按用户选择保留同步兜底；一次性主题返回崩溃仍按用户要求等复现。
