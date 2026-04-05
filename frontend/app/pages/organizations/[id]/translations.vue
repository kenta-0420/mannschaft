<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
</script>

<template>
  <div class="p-4">
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-bold">翻訳管理</h1>
    </div>
    <TranslationList :org-id="orgId" />
  </div>
</template>
