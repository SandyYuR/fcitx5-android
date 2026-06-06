# fcitx5-android docs branch

这是文档协作分支（`docs`），用于低门槛参与文档编写。

## 快速开始（只拉 docs 分支最新提交）

```bash
git clone --depth 1 --single-branch --branch docs https://github.com/fxliang/fcitx5-android.git
cd fcitx5-android
npm --prefix docs install
npm --prefix docs run dev
```

默认本地地址通常为：`http://localhost:5173/fcitx5-android/`

## 提交前检查

```bash
npm --prefix docs run build
git status
git add docs README.md
git commit -m "docs: update documentation"
```

## 提交流程

1. 有仓库写权限：直接推送到 `docs` 分支。  
2. 无写权限：推送到 fork 分支后发 Pull Request，目标分支选择 `docs`。

---

详细指南：`docs/about/contribute-docs.md`
