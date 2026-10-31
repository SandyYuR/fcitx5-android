<script setup lang="ts">
import { withBase } from 'vitepress'
</script>
<template>
  <aside class="home-start-note" aria-label="首次安装说明">
    <strong>首次使用：安装启用 → 导入方案 → 部署</strong>
    <p>APK 不预装中文方案和词典。<a :href="withBase('/guide/quick-start')">按步骤完成第一次中文输入</a>；升级前请先<a :href="withBase('/guide/installation')">备份数据</a>。</p>
  </aside>
</template>
<style scoped>
.home-start-note { max-width:620px; margin:24px 0 0; padding:14px 16px; border:1px solid var(--vp-c-divider); border-radius:12px; background:var(--vp-c-bg-soft); font-size:14px; line-height:1.75; }
strong { color:var(--vp-c-text-1); font-weight:600; }
p { color:var(--vp-c-text-2); margin:5px 0 0; }
a { color:var(--vp-c-brand-1); text-decoration:underline; text-underline-offset:3px; }
@media(max-width:959px) { .home-start-note { margin-left:auto; margin-right:auto; text-align:left; } }
</style>
