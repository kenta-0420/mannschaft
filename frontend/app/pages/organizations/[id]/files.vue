<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">ファイル共有</h1></div>
    <FileBrowser scope-type="ORGANIZATION" :scope-id="orgId" />
  </div>
</template>
