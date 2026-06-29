<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const { t } = useI18n()
</script>

<template>
  <div class="p-6">
    <PageHeader :title="t('nav.myFiles')" />
    <FileBrowser scope-type="PERSONAL" />
  </div>
</template>
