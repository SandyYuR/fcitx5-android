# 文档工作区

本站源码在 `docs` 分支；默认代码分支 `fx-rime-only` 不包含 `docs/`。站点默认介绍中州鹅 Rime 直连版。

- [快速参与](/about/contribute-docs)
- [分类、旧链接与检查约定](/maintainer/documentation)
- [全部文档](/contents)
- [维护资料](/maintainer/catalog)
- [靓企鹅中州韵历史档案](/legacy/)

```sh
npm --prefix docs ci
npm --prefix docs run dev
npm --prefix docs run build
python docs/scripts/check-links.py
python docs/scripts/check-legacy-docs.py
node docs/scripts/check-doc-catalog.mjs
```
