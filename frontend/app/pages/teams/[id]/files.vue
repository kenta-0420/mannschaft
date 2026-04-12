<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><PageHeader title="ファイル共有" /></div>
    <FileBrowser scope-type="TEAM" :scope-id="teamId" />
  </div>
</template>
