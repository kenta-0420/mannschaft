<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="ブログ・お知らせ" />
    </div>
    <BlogPostList scope-type="ORGANIZATION" :scope-id="orgId" />
  </div>
</template>
