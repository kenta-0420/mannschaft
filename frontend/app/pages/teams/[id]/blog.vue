<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">ブログ・お知らせ</h1></div>
    <BlogPostList scope-type="TEAM" :scope-id="teamId" />
  </div>
</template>
