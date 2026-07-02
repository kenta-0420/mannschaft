<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const showGuide = ref(false)
</script>

<template>
  <div>
    <PageHeader :title="$t('notification.pageTitle')" help @help="showGuide = true" />

    <div class="mx-auto max-w-2xl">
      <NotificationList />
    </div>

    <!-- 使い方説明モーダル -->
    <NotificationGuideModal v-model:visible="showGuide" />
  </div>
</template>
