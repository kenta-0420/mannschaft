<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})
</script>

<template>
  <div>
    <div class="mb-4">
      <h1 class="text-2xl font-bold">通知設定</h1>
    </div>

    <div class="mx-auto max-w-2xl">
      <NotificationPreferences />
    </div>
  </div>
</template>
