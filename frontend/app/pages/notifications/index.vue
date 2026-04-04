<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const router = useRouter()
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">通知</h1>
    </div>

    <div class="mx-auto max-w-2xl">
      <NotificationList />
    </div>
  </div>
</template>
