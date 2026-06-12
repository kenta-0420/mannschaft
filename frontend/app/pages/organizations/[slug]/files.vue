<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgSlug = String(route.params.slug)
</script>

<template>
  <div>
    <PageHeader title="ファイル共有" />
    <FileBrowser scope-type="ORGANIZATION" :scope-id="orgSlug" />
  </div>
</template>
