<script setup lang="ts">
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'
const { page, frontmatter } = useData()
const path = computed(() => page.value.relativePath)
const legacy = computed(() => path.value.startsWith('legacy/fcitx/'))
const legacyNotes = computed(() => /^legacy\/fcitx\/(notes|planning)\//.test(path.value))
const historical = computed(() => /^maintainer\/(history|audit|perf)\//.test(path.value))
const label = computed(() => legacyNotes.value ? '靓企鹅中州韵 · 后续批注与早期方案' : legacy.value ? '靓企鹅中州韵 · 原站历史文档' : historical.value ? '中州鹅 · 历史记录与验证证据' : path.value.startsWith('legacy/') ? '项目版本与历史档案' : path.value.startsWith('maintainer/') ? '中州鹅 · 维护文档' : '中州鹅 · 使用指南')
</script>

<template>
  <div v-if="!frontmatter.redirect" class="doc-context" :class="{ archived: legacy || historical }">
    <div class="doc-context-row">
      <span class="context-label">{{ label }}</span>
      <a :href="withBase(legacy ? '/legacy/' : path.startsWith('maintainer/') ? '/maintainer/catalog' : '/contents')">{{ legacy ? '版本说明' : '文档目录' }} <span aria-hidden="true">↗</span></a>
    </div>
    <p v-if="legacyNotes">本文是含 Fcitx 版本的批注或早期方案，与<a :href="withBase('/legacy/fcitx/contents')">冻结原站</a>分别保留。当前操作请查阅<a :href="withBase('/manual/DIRECT_RIME_USER_GUIDE_zh-CN')">中州鹅手册</a>。</p>
    <p v-else-if="legacy">本文保留含 Fcitx 中间层版本的原始说明。中州鹅的功能与操作请查阅<a :href="withBase('/manual/DIRECT_RIME_USER_GUIDE_zh-CN')">当前用户手册</a>。</p>
    <p v-else-if="historical">结论、提交、测试结果与安装包均对应文中记录时点。日常操作以<a :href="withBase('/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN')">现行维护手册</a>为准。</p>
  </div>
</template>
