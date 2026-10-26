<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const { t } = useI18n()
</script>

<template>
  <div class="mx-auto max-w-3xl px-6 pb-8">
    <PageHeader :title="t('nav.myFiles')" />
    <FileBrowser scope-type="PERSONAL" />
  </div>
</template>
