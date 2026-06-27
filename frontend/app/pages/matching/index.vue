<script setup lang="ts">
const { t } = useI18n()
definePageMeta({ middleware: 'auth' })
</script>

<template>
  <div>
    <PageHeader :title="t('matching.pageTitle')" />
    <MatchRequestList />
  </div>
</template>
