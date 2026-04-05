<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const router = useRouter()
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <div class="mb-6 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">メンション</h1>
    </div>
    <MentionList />
  </div>
</template>
