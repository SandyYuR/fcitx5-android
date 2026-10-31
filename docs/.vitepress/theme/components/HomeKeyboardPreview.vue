<script setup lang="ts">
import { withBase } from 'vitepress'
</script>
<template>
  <figure class="home-keyboard-preview">
    <img :src="withBase('/images/keyboard-android-preview-20261031.jpg')" width="834" height="676" alt="中州鹅 Android 主题编辑器中的键盘预览，取自模拟器实测截图" />
    <figcaption><a :href="withBase('/features/theme/theme-editor')">Android 主题编辑器实际预览</a> · 模拟器截图</figcaption>
  </figure>
</template>
<style scoped>
.home-keyboard-preview { width:min(100%,460px); margin:0; }
img { width:100%; height:auto; border:1px solid var(--vp-c-divider); border-radius:12px; box-shadow:0 12px 36px #0002; }
figcaption { margin-top:12px; text-align:center; font-size:12px; line-height:1.8; color:var(--vp-c-text-2); }
a { color:var(--vp-c-brand-1); }
</style>
