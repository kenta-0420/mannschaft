<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})
</script>

<template>
  <div>
    <div class="mx-auto max-w-2xl p-6">
      <PageHeader title="通知設定" />
      <NotificationPreferences />
    </div>
  </div>
</template>
