# fcitx5-android docs branch（中州鹅文档站）

这是 `SandyYuR/fcitx5-android` 的文档协作分支（`docs`），用于低门槛参与文档编写。站点整理中州鹅直连版的使用和维护文档，对应代码默认分支 `fx-rime-only`；安装包以 Release 或成功 CI 的源码提交、包名和签名为准。旧 Fcitx 版说明保留历史入口。

## 快速开始（只拉 docs 分支最新提交）

```bash
git clone --depth 1 --single-branch --branch docs https://github.com/SandyYuR/fcitx5-android.git
cd fcitx5-android
npm --prefix docs ci
npm --prefix docs run dev
```

默认本地地址通常为：`http://localhost:5173/fcitx5-android/`

## 提交前检查

```bash
npm --prefix docs run build
python docs/scripts/check-links.py
python docs/scripts/check-legacy-docs.py
node docs/scripts/check-doc-catalog.mjs
node docs/scripts/check-doc-search.mjs
git diff --check
git status --short
git add <修改的文件路径>
git commit -m "文档(模块): 具体修改内容"
```

## 提交流程

1. 从 `docs` 新开文档分支，提交 Pull Request，目标选择 `docs`；PR 先完成站点构建与内部链接检查。
2. 合并到 `docs` 后，由 **Docs** 工作流发布 GitHub Pages。描述代码改动时，先核对默认分支的对应提交与构建状态。

---

详细指南：`docs/about/contribute-docs.md`
