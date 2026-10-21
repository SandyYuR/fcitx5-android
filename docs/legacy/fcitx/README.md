# 文档协作快速开始（docs 分支）

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/README.md)
:::


本文面向只参与文档编写的贡献者，推荐只克隆 `docs` 分支最新提交，减少下载量与环境准备复杂度。

## 1. 克隆 docs 分支最新提交

```bash
git clone --depth 1 --single-branch --branch docs https://github.com/SandyYuR/fcitx5-android.git
cd fcitx5-android
```

如果使用自己的 fork：

```bash
git clone --depth 1 --single-branch --branch docs https://github.com/<your-name>/fcitx5-android.git
cd fcitx5-android
```

## 2. 安装文档依赖

```bash
npm --prefix docs install
```

## 3. 本地预览

```bash
npm --prefix docs run dev
```

默认访问地址通常是：`http://localhost:5173/fcitx5-android/`

## 4. 提交前检查

```bash
npm --prefix docs run build
git status
git add docs
git commit -m "docs: update documentation"
```

## 5. 提交修改

1. 有仓库写权限：直接推送到 `docs` 分支（GitHub Actions 的 **Docs** 工作流会自动构建并发布到 GitHub Pages）。
2. 无写权限：推送到 fork 分支后发 Pull Request，目标分支选择 `docs`。

---

详细说明见：`docs/about/contribute-docs.md`
