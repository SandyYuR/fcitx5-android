# 文档分类与维护约定

站点由 `docs` 分支构建。用户看到的默认内容对应 **中州鹅 Rime 直连版**；带 Fcitx 中间层的 **靓企鹅中州韵**单独归档。

## 资料放在哪里

| 内容 | 目录 | 维护方式 |
| --- | --- | --- |
| 安装、迁移、概念 | `docs/guide/` | 随当前行为更新 |
| 用户功能与设置 | `docs/features/` | 与当前源码逐项核对 |
| 完整用户手册 | `docs/manual/DIRECT_RIME_USER_GUIDE_zh-CN.md` | 把关键流程串起来，细节链接功能页 |
| 现行维护与环境 | `docs/maintainer/` 的维护手册和环境页 | 固定操作约束，变化的数据现场查询 |
| 当前接口和数据协议 | `docs/maintainer/implementation/` | 保持契约、预算、兼容边界和测试入口准确 |
| 代码与文档核对 | `docs/maintainer/changes/` | 固定来源提交、逐项登记覆盖与缺口 |
| 中州鹅阶段报告 | `docs/maintainer/history/` | 保留当时事实，不把旧结果改写为新结果 |
| 原始验证与性能证据 | `docs/maintainer/audit/`、`perf/` 及对应附件 | 正文说明来源，原始文件保留；完整索引见证据目录 |
| 靓企鹅中州韵原站 | `docs/legacy/fcitx/` 原恢复清单中的页面 | 冻结来源与原文，内部链接、字节校验由恢复清单守护 |
| 旧版后续批注、另存原方案 | `docs/legacy/fcitx/notes/`、`planning/` | 与冻结原站分开保存 |

旧地址的小页面只有 `redirect` 与目标链接，不计作重复正文，不进入站内全文搜索。浏览器跳转会保留查询和原章节片段。
本次迁移清单见[2026-10-30 页面迁移清单](/maintainer/site-reorganization-2026-10-30.json)，包含来源提交、原文件与归档位置。

## 增补一项功能

先读取默认分支实际代码、资源文案与相关测试，再更新对应功能页和用户手册。构建、引擎、CI 或协议改动同时更新维护页。
产品首页只放主要能力和入口，不复制历史提交数量、临时 PR 状态或实验 APK 下载地址。
“默认开启”“可共存”“已验证”“密钥会迁移”等描述必须有直接依据；没有实测的服务或设备范围不能写成验证通过。

## 新页面与导航

1. 将正文放到相应目录，用清楚的一级标题说明内容。
2. 在 `docs/.vitepress/navigation.ts` 调整导航、侧栏或关联入口，由 `config.ts` 加载。
3. `build-catalog.mjs` 从源文件生成全站与维护目录，自动收录正文并验证跳转目标。
4. 页面改名时保留旧地址跳转，更新站内链接；原始日志、ZIP 与 JSON 不因整理目录丢弃。
5. 运行下面的门禁并检查真实页面。

```sh
npm --prefix docs ci
npm --prefix docs run build
python docs/scripts/check-links.py
python docs/scripts/check-legacy-docs.py
node docs/scripts/check-doc-catalog.mjs
git diff --check
```

目录由构建自动生成；`docs/.vitepress/generated/` 不提交。新增文件后重新运行构建或重启 dev，使目录重新收录。
`copy-downloads.mjs` 负责把 Markdown 引用的非页面附件按原相对地址发布，不能仅以 VitePress 编译成功判断附件存在。

## Windows 预览

使用可用的 `python`，不要调用 WindowsApps 的 `python3` 占位程序。文档构建不需要 Android 工具链。
若 esbuild 报用户临时目录权限错误，可在**当前 PowerShell 进程**将 `TMP`/`TEMP` 指向工作区中的临时目录，再构建；不要为此修改系统级环境或安全设置。
历史文本在 Windows checkout 可能被 Git 转为 CRLF；完整性检查只允许文本换行归一化，原始 ZIP 和二进制附件仍严格逐字节校验。

## 发布与历史解释

Docs 工作流在 PR 中构建并运行检查，合入 `docs` 后部署。外部推送、PR、发布和工作流调度需符合工作区授权约定，文档编辑本身不代表已经发布。
日期报告中写“当时通过”的结果继续保留；现行手册只链接它的条件与证据，不自动继承其测试结论。
