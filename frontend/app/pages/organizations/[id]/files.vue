<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgId = String(route.params.id)
</script>

<template>
  <div>
    <PageHeader title="ファイル共有" />
    <FileBrowser scope-type="ORGANIZATION" :scope-id="orgId" />
  </div>
</template>
