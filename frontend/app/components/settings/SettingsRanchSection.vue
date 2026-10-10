<script setup lang="ts">
const { t } = useI18n()
</script>
<template><SectionCard :title="t('ranch.title')"><p>{{ t('ranch.avatar.description') }}</p><NuxtLink to="/my/ranch" class="flex min-h-11 items-center text-primary">{{ t('ranch.settings.title') }}</NuxtLink><NuxtLink to="/my/ranch/results" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.title') }}</NuxtLink></SectionCard></template>
