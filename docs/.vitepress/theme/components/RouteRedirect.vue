<script setup lang="ts">
import { onMounted } from 'vue'
import { useData, withBase } from 'vitepress'
const { frontmatter } = useData()
onMounted(() => {
  const target = frontmatter.value.redirect
  if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) {
    window.location.replace(withBase(target) + window.location.search + window.location.hash)
  }
})
</script>
<template><span class="sr-only">正在打开归档后的页面；原有章节位置会保留。</span></template>
