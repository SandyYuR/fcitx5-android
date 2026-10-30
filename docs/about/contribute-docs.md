# 参与文档修改

本站使用 VitePress，源码位于 `docs` 分支的 `docs/`。只修改文档不需要 Android 构建环境；准备 Git、Node.js 20 或更高版本、npm 和 Python 即可。

## 准备工作区

```sh
git clone --depth 1 --single-branch --branch docs https://github.com/SandyYuR/fcitx5-android.git
cd fcitx5-android
npm --prefix docs ci
npm --prefix docs run dev
```

跟随命令输出打开本地地址；站点路径带 `/fcitx5-android/`。已有完整工作区时使用独立的 `docs` worktree，不在代码分支新建 `docs/`。

## 先选择资料类型

- 用户现在要怎么做：`guide/`、`features/` 和当前用户手册。
- 开发者现在怎么维护：维护手册、环境说明和 `maintainer/implementation/`。
- 记录某次试验或合入：`maintainer/history/`，注明日期、提交、条件及未验证范围。
- 含 Fcitx 的旧版：`legacy/fcitx/`；冻结原站不改写，后续批注独立保存。

详细存放规则、旧链接处理和历史保护见[文档维护约定](/maintainer/documentation)。

## 编辑与预览

主要导航在 `docs/.vitepress/navigation.ts`，配置由 `config.ts` 加载；页面样式和目录组件在 `docs/.vitepress/theme/`。
新增正文会在重新构建或重启 dev 后进入[全站目录](/contents)。请同时增加合适的侧栏或相关页面入口，不让新文档只能靠搜索找到。

写当前功能前核对默认分支代码、实际资源文案与相关测试。别把旧 Fcitx 的操作、历史 PR 状态或临时实验结论当成当前行为。
示例保持可复制；已有锚点尽量保留，改路径时加跳转页。

## 检查后提交

```sh
npm --prefix docs run build
python docs/scripts/check-links.py
python docs/scripts/check-legacy-docs.py
node docs/scripts/check-doc-catalog.mjs
git diff --check
git status --short
```

另检查桌面和手机宽度下的导航、目录筛选、长表格、代码块与图片，确认原始 ZIP/JSON 可下载。
按明确文件路径暂存改动，提交标题使用 `文档(模块): 具体修改内容`，不要批量暂存无关文件。

有授权时提交面向 **`docs` 分支**的 PR；说明修正了什么、核对的代码提交和实际运行的检查。PR 的 Docs 工作流只验证，合入 `docs` 后部署到 Pages。
源码和文档分开维护，主代码分支只保留 README 入口及随接口同步的 AIDL 协议。
