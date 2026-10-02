<script setup lang="ts">
definePageMeta({
  middleware: ['auth'],
})

const router = useRouter()
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h2 class="text-2xl font-semibold">タイムライン</h2>
    </div>

    <TimelineFeed my-feed />
  </div>
</template>
