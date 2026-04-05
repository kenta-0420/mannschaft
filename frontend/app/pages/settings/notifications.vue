<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">通知設定</h1>
    </div>

    <div class="mx-auto max-w-2xl">
      <NotificationPreferences />
    </div>
  </div>
</template>
