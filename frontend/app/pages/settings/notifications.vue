<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})
</script>

<template>
  <div>
    <BackButton to="/settings" />
    <PageHeader title="通知設定" />

    <div class="mx-auto max-w-2xl">
      <NotificationPreferences />
    </div>
  </div>
</template>
