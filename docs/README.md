# 文档协作快速开始（docs 分支）

本文面向只参与文档编写的贡献者，推荐只克隆 `docs` 分支最新提交，减少下载量与环境准备复杂度。

## 1. 克隆 docs 分支最新提交

```bash
git clone --depth 1 --single-branch --branch docs https://github.com/fxliang/fcitx5-android.git
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

1. 有仓库写权限：直接推送到 `docs` 分支。  
2. 无写权限：推送到 fork 分支后发 Pull Request，目标分支选择 `docs`。

---

详细说明见：`docs/about/contribute-docs.md`
