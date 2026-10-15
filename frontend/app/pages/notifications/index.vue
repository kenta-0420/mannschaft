<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const showGuide = ref(false)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="$t('notification.pageTitle')" help @help="showGuide = true" />

    <NotificationList />

    <!-- 使い方説明モーダル -->
    <NotificationGuideModal v-model:visible="showGuide" />
  </div>
</template>
