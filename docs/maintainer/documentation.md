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
| 代码与文档核对 | `docs/maintainer/changes/` | 索引页只做入口；每轮新增日期页与 `commit-map-*.json`，固定来源提交、逐项登记覆盖与缺口 |
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

## 同一事实只写一处

会随版本变化的事实只在下表的唯一来源维护，其他页面写结论并链接，不复制版本号、ABI 列表或日期：

| 事实 | 唯一来源 | 其他页面 |
| --- | --- | --- |
| 发布 ABI、Release 资产与 CI 产物命名（用户） | [安装与升级](/guide/installation#下载主应用) | 用户手册、更新检查只写“按设备 ABI 选择”并链接 |
| 各 CI 的构建 ABI 与发布条件（维护） | [维护手册 · 按改动选择验证门禁](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN#按改动选择验证门禁) | 引擎更新页链接，不重列矩阵 |
| 现行 SDK 锁（标签、librime 提交、摘要） | `native/rime-sdk.lock.json` 与[引擎更新 · 两条链路](/maintainer/engine-update#两条链路) | 维护手册、功能页、正确性约束只写行为起始版本 |
| 历次 SDK 交付与验证 | 对应日期的 `maintainer/changes/` 记录 | 引擎更新页保留版本索引表 |
| 日常不需要引擎插件 APK | [版本共存与数据隔离](/guide/builds-and-plugins) | 介绍、FAQ、致谢只写结论 |
| JS 插件与 AI 助手的合入版本 | [JS 插件](/features/voice-input-js) | AI 助手页、两份参考页、宿主协议链接 |
| AI 助手操作流程 | [AI 文本助手](/features/ai-assistant) | JS 插件页只保留入口 |
| 分体、浮动与单手键盘的开关、默认值与互斥关系 | [分体、浮动与单手键盘](/features/keyboard/keyboard-modes) | 三个形态页只写各自操作 |
| 引擎变体、万象与自定义方案更新的细节 | [Rime 引擎变体](/features/rime-engine-variants) · [Rime 方案更新](/features/rime-scheme-updates) | Rime 设置页保留同名小节作摘要和锚点 |
| 宏编辑器键名与 X11 键名的区别 | [Fcitx 键名速查](/features/keys/fcitx-keys#编辑器键名与手写键名) | MacroKey 页链接 |
| 现行功能页与源码入口 | [覆盖表](/maintainer/changes/feature-coverage) | 对照页、索引只链接 |

## 功能页结构

功能页按“概述 → 入口与设置 → 行为细节 → 相关页面”组织，「相关页面」固定为最后一节；后补的细节并入对应章节作为三级标题，不追加在「相关页面」之后。三级标题沿用原二级标题文字，原有锚点保持有效。移动页面时旧地址保留 `redirect` 小页面，并同步导航、[覆盖表](/maintainer/changes/feature-coverage)与站内链接。

## 提交对照的登记方式

每轮代码核对新增 `maintainer/changes/YYYY-MM-DD-*.md`，并登记到[对照索引](/maintainer/changes/)与侧栏“代码与文档对照”的对应月份。逐提交核对同时提交 `commit-map-YYYY-MM-DD.json`，格式与校验规则见[对照索引的记录约定](/maintainer/changes/#记录约定)；`check-doc-catalog.mjs` 会检查每个提交都映射到现行文档。对照页记录的提交后来被改写或已合入时，只在页首加“后续更正／后续复核”引用块。

## 新页面与导航

1. 将正文放到相应目录，用清楚的一级标题说明内容。
2. 在 `docs/.vitepress/navigation.ts` 调整导航、侧栏或关联入口，由 `config.ts` 加载。
3. `build-catalog.mjs` 从源文件生成全站与维护目录，自动收录正文并验证跳转目标。
4. 页面改名时保留旧地址跳转，更新站内链接；原始日志、ZIP 与 JSON 不因整理目录丢弃。
5. 运行下面的门禁并检查真实页面。

新增现行功能页时同步 `maintainer/changes/feature-coverage.md` 的源码入口；目录检查会逐页验证映射。门禁分别报告固定基线的映射数量与当前功能页数量，不把旧报告中的计数当作现状。

视觉规范的简明入口为代码分支根 `DESIGN.md`，详细组件说明放在[界面设计与共用组件](/maintainer/implementation/design-system)。截图区分组件渲染、模拟器与真机；色板示例不代表每位用户的实际外观设置。

```sh
npm --prefix docs ci
npm --prefix docs run build
python docs/scripts/check-links.py
python docs/scripts/check-legacy-docs.py
node docs/scripts/check-doc-catalog.mjs
node docs/scripts/check-doc-search.mjs
python docs/scripts/check-evidence-index.py
python docs/scripts/check-coverage-sources.py --code-dir <代码分支工作区>
git diff --check
```

目录由构建自动生成；`docs/.vitepress/generated/` 不提交。新增文件后重新运行构建或重启 dev，使目录重新收录。
`copy-downloads.mjs` 负责把 Markdown 引用的非页面附件按原相对地址发布，不能仅以 VitePress 编译成功判断附件存在。

搜索的索引构建与浏览器查询共用 `.vitepress/search-shared.mjs` 的切词规则，支持中文词组出现在句子中间以及“宏”等单字查询。构建后从 VitePress 的真实索引派生现行索引，默认仅下载该小索引；勾选历史时才加载全集。索引分批恢复，保持加载期间的操作响应。现行功能覆盖表继续参与默认检索；固定日期的核对报告、提交索引、历史证据与上游参考需勾选后查看。目录分组和页面上下文也使用相同的历史分类，不改历史原文。

`check-evidence-index.py` 不需要先构建：它按 `copy-downloads.mjs` 的规则列出 `maintainer/` 下实际发布的附件目录，要求每个目录都在[验证证据目录](/maintainer/evidence)中登记，登记方式有两种：逐文件小节（2026-11-06 前的旧目录），或标记块内表格的一行（之后的报告目录，由报告逐项链接附件）。不在 `audit/`、`perf/` 下的目录，如果其中文件已被页面链接或嵌入（如实现页旁的示意图），可以不登记。新增附件目录后运行 `python docs/scripts/check-evidence-index.py --write` 追加表格行，再核对自动选取的说明链接；逐文件小节缺文件、列出不存在的文件、表格行对应目录已删除或链接页面不存在时都会失败。

`check-coverage-sources.py` 把[覆盖表](/maintainer/changes/feature-coverage)中链接的本仓库源码路径与代码分支的文件列表对照：现行各节须使用固定提交 SHA，分支链接或缺失路径会失败；「增量记录」保留当时的事实，这两种情况只提示。本地可用 `--code-dir` 指向代码仓库，默认检查其 `HEAD`，也可加 `--ref origin/fx-rime-only` 对照已获取的远端默认分支。和 CI 一样，这个检查只读取 Git 跟踪树。也可用 `--code-tree` 传入 `git -c core.quotePath=false ls-tree -r --name-only fx-rime-only` 的输出；两者都不传时跳过。Docs 工作流会以无 blob 的浅克隆取得 `fx-rime-only` 的文件列表，并以 `--require` 运行。代码改名或删除文件后，下一次文档 PR 需要先更新覆盖表。

目录的“主题”由 `build-catalog.mjs` 的 `topicFor` 决定：先按目录（`maintainer/perf/` 一律归“性能与诊断”），再按路径关键词表，都不匹配时维护资料归“构建与审阅”、用户页面归“使用与维护”。新增一类页面时优先在关键词表里补规则，不要依赖兜底分类。

修改搜索后运行 `check-doc-search.mjs`：它读取实际发布索引，检查中文功能词、英文 SDK 查询、现行覆盖表和历史范围；还需检查桌面/手机上的检索、方向键、Enter、Esc 与焦点恢复。

## Windows 预览

使用可用的 `python`，不要调用 WindowsApps 的 `python3` 占位程序。文档构建不需要 Android 工具链。
若 esbuild 报用户临时目录权限错误，可在**当前 PowerShell 进程**将 `TMP`/`TEMP` 指向工作区中的临时目录，再构建；不要为此修改系统级环境或安全设置。
历史文本在 Windows checkout 可能被 Git 转为 CRLF；完整性检查只允许文本换行归一化，原始 ZIP 和二进制附件仍严格逐字节校验。

## 品牌图标来源与同步

当前白鹅图标由用户确认的展翅伸颈、咬住「㞢」图稿提取，彩色、浅色与深色单色 SVG 位于代码仓库的 `artwork/`。应用的矢量资源在 `app/src/main/res/drawable/` 与 `drawable-night/`，旧版 Android 桌面所需的五档密度 PNG 在 `app/src/fx/res/mipmap-*/`；自适应图标保留 108 × 108 画布，轮廓主体位于中心半径 32.5 的圆内。

浅色单色版使用 10% 不透明度的鹅身填色、72% 的细描边、60% 的喙脚和 90% 的眼睛与「㞢」；`drawable-night/` 独立保留 100%／65%／90% 的鹅身、喙脚与字形，眼睛镂空。不要共用一组透明度而使浅色调整连带改变深色。桌面按 alpha 遮罩着色，实际取色及图标缓存刷新由启动器管理，不能直接把带底色的彩色图转灰度。状态栏与小尺寸应用内标识保留实心轮廓以保证辨识度，Debug 字样已转为路径，不依赖设备字体。设备回归检查日夜资源的实际像素透明度、66 dp 安全圆及页面截图。

文档站的 `docs/public/logo.svg` 与代码仓库彩色 SVG 一致，`logo.png` 保留为旧地址兼容资源；favicon 和 Apple touch icon 同批更新。独立网页编辑器使用「中州鹅」名称及用户确认的白鹅矢量 favicon。`docs/public/legacy/fcitx/logo.png` 属于冻结历史原站，保留原字节，不随现行标识替换。

## 发布与历史解释

Docs 工作流在 PR 中构建并运行检查，合入 `docs` 后部署。外部推送、PR、发布和工作流调度需符合工作区授权约定，文档编辑本身不代表已经发布。
日期报告中写“当时通过”的结果继续保留；现行手册只链接它的条件与证据，不自动继承其测试结论。

获准整理主分支历史时，另建旧新 SHA 对应表并记录最终内容树是否一致，不批量改写旧报告的来源和验收结果。应保留原发布标签供追溯，并分别核对本地与远端状态；已缺失的远端标签如实登记，不自动重建发布。纯提交归并仍会影响 Git 距离生成的版本名，应同时核对应用更新器的数字比较规则。实例见[2026-11-07 提交分类整理](changes/2026-11-07-history-squash.md)。
