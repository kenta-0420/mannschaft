<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})
</script>

<template>
  <div>
    <PageHeader title="通知設定" back-to="/settings" />

    <div class="mx-auto max-w-2xl">
      <NotificationPreferences />
    </div>
  </div>
</template>
