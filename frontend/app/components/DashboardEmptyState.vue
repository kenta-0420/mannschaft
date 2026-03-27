<script setup lang="ts">
defineProps<{
  icon?: string
  message: string
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center py-8 text-surface-400">
    <i :class="icon ?? 'pi pi-inbox'" class="mb-2 text-3xl" />
    <p class="text-sm">{{ message }}</p>
  </div>
</template>
