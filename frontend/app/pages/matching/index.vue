<script setup lang="ts">
const { t } = useI18n()
definePageMeta({ middleware: 'auth' })
const showGuide = ref(false)
</script>

<template>
  <div>
    <PageHeader :title="t('matching.pageTitle')" help @help="showGuide = true" />
    <MatchRequestList />
    <MatchingGuideModal v-model:visible="showGuide" />
  </div>
</template>
