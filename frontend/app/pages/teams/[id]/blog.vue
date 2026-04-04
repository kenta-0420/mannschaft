<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const teamId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">ブログ・お知らせ</h1>
    </div>
    <BlogPostList scope-type="TEAM" :scope-id="teamId" />
  </div>
</template>
