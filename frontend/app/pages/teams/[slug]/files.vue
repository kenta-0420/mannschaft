<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
</script>

<template>
  <div>
    <div class="mb-4"><PageHeader title="ファイル共有" /></div>
    <FileBrowser scope-type="TEAM" :scope-id="teamSlug" />
  </div>
</template>
