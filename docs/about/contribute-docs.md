# 参与文档修改

本文档放在仓库的 `docs/` 目录中，使用 VitePress 构建。只修改文档时通常不需要准备 Android 构建环境，只需要 Git、Node.js 和 npm。

## 拉取仓库

```bash
git clone https://github.com/fxliang/fcitx5-android.git
# 或者 git clone https://gitlab.com/fxliang/fcitx5-android.git -b docs
cd fcitx5-android
# 直接拉取docs分支的话就不用下面的操作了
git checkout docs
```

如果你从自己的 fork 修改，可以把地址换成你的 fork：

```bash
git clone https://github.com/<your-name>/fcitx5-android.git
cd fcitx5-android
git checkout docs
```

## 准备文档环境

首次进入仓库后安装 docs 依赖：

```bash
npm --prefix docs install
```

依赖只安装在 `docs/` 下，不会影响 Android 工程的 Gradle 依赖。

## 本地预览

启动 VitePress 开发服务器：

```bash
npm --prefix docs run dev
```

命令输出中会显示本地访问地址，通常是 `http://localhost:5173/fcitx5-android/`。修改 Markdown 后页面会自动刷新。

## 修改页面

- 首页：`docs/index.md`
- 导航和侧栏：`docs/.vitepress/config.ts`
- 指南：`docs/guide/`
- 功能说明：`docs/features/`
- 疑难解答：`docs/troubleshooting/`
- 关于页面：`docs/about/`
- 静态图片：`docs/public/`

新增页面后，如果希望它出现在导航或侧栏中，需要同时修改 `docs/.vitepress/config.ts`。

## 构建测试

提交前至少运行一次构建：

```bash
npm --prefix docs run build
```

如果想查看构建后的静态页面：

```bash
npm --prefix docs run preview
```

## 提交修改

查看修改：

```bash
git status
git diff
```

暂存文档修改：

```bash
git add docs
```

提交：

```bash
git commit -m "docs: update documentation"
```

## 提交 Pull Request

如果你没有本仓库的直接写入权限，推荐从自己的 fork 发 Pull Request：

```bash
git checkout -b docs/update-something
git add docs
git commit -m "docs: update documentation"
git push origin docs/update-something
```

然后在 GitHub 上打开你的 fork，创建 Pull Request。目标仓库选择 `fxliang/fcitx5-android`，目标分支选择 `fx`。

PR 描述里建议写清楚：

- 修改了哪些页面
- 修正了什么错误或补充了什么内容
- 是否已运行 `npm --prefix docs run build`

## 修改建议

- 优先按代码实现描述功能，不确定时请标注“以当前版本为准”或先查对应源码。
- 示例 JSON、命令和路径尽量可直接复制运行。
- 新增功能页时同步检查导航、侧栏、相关页面链接。
- 避免把未验证的计划功能写成已经实现的功能。
